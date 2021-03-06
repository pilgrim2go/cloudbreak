package com.sequenceiq.cloudbreak.service.cluster;

import static com.sequenceiq.cloudbreak.service.cluster.DataNodeUtils.sortByUsedSpace;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sequenceiq.ambari.client.AmbariClient;
import com.sequenceiq.cloudbreak.conf.ReactorConfig;
import com.sequenceiq.cloudbreak.controller.BadRequestException;
import com.sequenceiq.cloudbreak.controller.InternalServerException;
import com.sequenceiq.cloudbreak.controller.NotFoundException;
import com.sequenceiq.cloudbreak.controller.json.HostGroupAdjustmentJson;
import com.sequenceiq.cloudbreak.domain.Blueprint;
import com.sequenceiq.cloudbreak.domain.CbUser;
import com.sequenceiq.cloudbreak.domain.Cluster;
import com.sequenceiq.cloudbreak.domain.HostMetadata;
import com.sequenceiq.cloudbreak.domain.InstanceMetaData;
import com.sequenceiq.cloudbreak.domain.Stack;
import com.sequenceiq.cloudbreak.domain.Status;
import com.sequenceiq.cloudbreak.domain.StatusRequest;
import com.sequenceiq.cloudbreak.logger.MDCBuilder;
import com.sequenceiq.cloudbreak.repository.ClusterRepository;
import com.sequenceiq.cloudbreak.repository.HostMetadataRepository;
import com.sequenceiq.cloudbreak.repository.InstanceMetaDataRepository;
import com.sequenceiq.cloudbreak.repository.RetryingStackUpdater;
import com.sequenceiq.cloudbreak.repository.StackRepository;
import com.sequenceiq.cloudbreak.service.DuplicateKeyValueException;
import com.sequenceiq.cloudbreak.service.cluster.event.ClusterStatusUpdateRequest;
import com.sequenceiq.cloudbreak.service.cluster.event.UpdateAmbariHostsRequest;
import com.sequenceiq.cloudbreak.service.cluster.filter.AmbariHostFilterService;

import groovyx.net.http.HttpResponseException;
import reactor.core.Reactor;
import reactor.event.Event;

@Service
public class AmbariClusterService implements ClusterService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AmbariClusterService.class);
    private static final String MASTER_CATEGORY = "MASTER";
    private static final String DATANODE = "DATANODE";
    private static final double SAFETY_PERCENTAGE = 1.2;

    @Autowired
    private StackRepository stackRepository;

    @Autowired
    private ClusterRepository clusterRepository;

    @Autowired
    private RetryingStackUpdater stackUpdater;

    @Autowired
    private InstanceMetaDataRepository instanceMetadataRepository;

    @Autowired
    private HostMetadataRepository hostMetadataRepository;

    @Autowired
    private Reactor reactor;

    @Autowired
    private AmbariClientService clientService;

    @Autowired
    private AmbariConfigurationService configurationService;

    @Autowired
    private AmbariHostFilterService hostFilterService;

    @Override
    public void create(CbUser user, Long stackId, Cluster cluster) {
        Stack stack = stackRepository.findOne(stackId);
        MDCBuilder.buildMdcContext(stack);
        LOGGER.info("Cluster requested [BlueprintId: {}]", cluster.getBlueprint().getId());
        if (stack.getCluster() != null) {
            throw new BadRequestException(String.format("A cluster is already created on this stack! [cluster: '%s']", stack.getCluster()
                    .getName()));
        }
        cluster.setOwner(user.getUserId());
        cluster.setAccount(user.getAccount());
        try {
            cluster = clusterRepository.save(cluster);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateKeyValueException(cluster.getName(), ex);
        }
        stack = stackUpdater.updateStackCluster(stack.getId(), cluster);
        LOGGER.info("Publishing {} event", ReactorConfig.CLUSTER_REQUESTED_EVENT);
        reactor.notify(ReactorConfig.CLUSTER_REQUESTED_EVENT, Event.wrap(stack));
    }

    @Override
    public Cluster retrieveCluster(Long stackId) {
        Stack stack = stackRepository.findOne(stackId);
        return stack.getCluster();
    }

    @Override
    public String getClusterJson(String ambariIp, Long stackId) {
        Stack stack = stackRepository.findOne(stackId);
        MDCBuilder.buildMdcContext(stack);
        AmbariClient ambariClient = clientService.create(stack);
        try {
            String clusterJson = ambariClient.getClusterAsJson();
            if (clusterJson == null) {
                throw new InternalServerException(String.format("Cluster response coming from Ambari server was null. [Ambari Server IP: '%s']", ambariIp));
            }
            return clusterJson;
        } catch (HttpResponseException e) {
            if ("Not Found".equals(e.getMessage())) {
                throw new NotFoundException("Ambari blueprint not found.", e);
            } else {
                throw new InternalServerException("Something went wrong", e);
            }
        }
    }

    @Override
    public void updateHosts(Long stackId, HostGroupAdjustmentJson hostGroupAdjustment) {
        Stack stack = stackRepository.findOneWithLists(stackId);
        Cluster cluster = stack.getCluster();
        MDCBuilder.buildMdcContext(cluster);
        boolean decommissionRequest = validateRequest(stack, hostGroupAdjustment);
        List<HostMetadata> downScaleCandidates = new ArrayList<>();
        if (decommissionRequest) {
            AmbariClient ambariClient = clientService.create(stack);
            int replication = getReplicationFactor(ambariClient);
            String hostGroup = hostGroupAdjustment.getHostGroup();
            Set<HostMetadata> hostsInHostGroup = hostMetadataRepository.findHostsInHostgroup(hostGroup, cluster.getId());
            List<HostMetadata> filteredHostList = hostFilterService.filterHostsForDecommission(stack, hostsInHostGroup);
            verifyNodeCount(cluster, replication, hostGroupAdjustment.getScalingAdjustment(), filteredHostList);
            if (doesHostGroupContainDataNode(ambariClient, cluster.getBlueprint().getBlueprintName(), hostGroup)) {
                downScaleCandidates = checkAndSortByAvailableSpace(stack, ambariClient, replication,
                        hostGroupAdjustment.getScalingAdjustment(), filteredHostList);
            } else {
                downScaleCandidates = filteredHostList;
            }
        }
        LOGGER.info("Cluster update requested [BlueprintId: {}]", cluster.getBlueprint().getId());
        LOGGER.info("Publishing {} event", ReactorConfig.UPDATE_AMBARI_HOSTS_REQUEST_EVENT);
        reactor.notify(ReactorConfig.UPDATE_AMBARI_HOSTS_REQUEST_EVENT, Event.wrap(
                new UpdateAmbariHostsRequest(stackId, hostGroupAdjustment, downScaleCandidates, decommissionRequest)));
    }

    @Override
    public void updateStatus(Long stackId, StatusRequest statusRequest) {
        Stack stack = stackRepository.findOne(stackId);
        Cluster cluster = stack.getCluster();
        MDCBuilder.buildMdcContext(stack.getCluster());
        long clusterId = cluster.getId();
        Status clusterStatus = cluster.getStatus();
        Status stackStatus = stack.getStatus();
        if (statusRequest.equals(StatusRequest.STARTED)) {
            if (Status.START_IN_PROGRESS.equals(stackStatus)) {
                LOGGER.info("Stack is starting, set cluster state to: {}", Status.START_REQUESTED);
                cluster.setStatus(Status.START_REQUESTED);
                clusterRepository.save(cluster);
            } else {
                if (!Status.STOPPED.equals(clusterStatus)) {
                    throw new BadRequestException(
                            String.format("Cannot update the status of cluster '%s' to STARTED, because it isn't in STOPPED state.", clusterId));
                }
                if (!Status.AVAILABLE.equals(stackStatus)) {
                    throw new BadRequestException(
                            String.format("Cannot update the status of cluster '%s' to STARTED, because the stack is not AVAILABLE", clusterId));
                }
                cluster.setStatus(Status.START_IN_PROGRESS);
                clusterRepository.save(cluster);
                LOGGER.info("Publishing {} event", ReactorConfig.CLUSTER_STATUS_UPDATE_EVENT);
                reactor.notify(ReactorConfig.CLUSTER_STATUS_UPDATE_EVENT,
                        Event.wrap(new ClusterStatusUpdateRequest(stack.getId(), statusRequest)));
            }
        } else {
            if (!Status.AVAILABLE.equals(clusterStatus)) {
                throw new BadRequestException(
                        String.format("Cannot update the status of cluster '%s' to STOPPED, because it isn't in AVAILABLE state.", clusterId));
            }
            if (!Status.AVAILABLE.equals(stackStatus) && !Status.STOP_REQUESTED.equals(stackStatus)) {
                throw new BadRequestException(
                        String.format("Cannot update the status of cluster '%s' to STARTED, because the stack is not AVAILABLE", clusterId));
            }
            cluster.setStatus(Status.STOP_IN_PROGRESS);
            clusterRepository.save(cluster);
            LOGGER.info("Publishing {} event", ReactorConfig.CLUSTER_STATUS_UPDATE_EVENT);
            reactor.notify(ReactorConfig.CLUSTER_STATUS_UPDATE_EVENT,
                    Event.wrap(new ClusterStatusUpdateRequest(stack.getId(), statusRequest)));
        }
    }

    private int getReplicationFactor(AmbariClient ambariClient) {
        try {
            Map<String, String> configuration = configurationService.getConfiguration(ambariClient);
            return Integer.parseInt(configuration.get(ConfigParam.DFS_REPLICATION.key()));
        } catch (ConnectException e) {
            LOGGER.error("Cannot connect to Ambari to get the configuration", e);
            throw new BadRequestException("Cannot connect to Ambari");
        }
    }

    private boolean validateRequest(Stack stack, HostGroupAdjustmentJson hostGroupAdjustment) {
        MDCBuilder.buildMdcContext(stack.getCluster());
        int scalingAdjustment = hostGroupAdjustment.getScalingAdjustment();
        boolean downScale = scalingAdjustment < 0;
        if (scalingAdjustment == 0) {
            throw new BadRequestException("No scaling adjustments specified. Nothing to do.");
        }
        if (!downScale) {
            validateUnregisteredHosts(stack, scalingAdjustment);
        } else {
            validateRegisteredHosts(stack, hostGroupAdjustment);
            validateComponentsCategory(stack, hostGroupAdjustment);
        }
        validateHostGroup(stack, hostGroupAdjustment);
        return downScale;
    }

    private void validateComponentsCategory(Stack stack, HostGroupAdjustmentJson hostGroupAdjustment) {
        AmbariClient ambariClient = clientService.create(stack);
        Cluster cluster = stack.getCluster();
        MDCBuilder.buildMdcContext(cluster);
        String hostGroup = hostGroupAdjustment.getHostGroup();
        Blueprint blueprint = cluster.getBlueprint();
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode root = mapper.readTree(blueprint.getBlueprintText());
            String blueprintName = root.path("Blueprints").path("blueprint_name").asText();
            Map<String, String> categories = ambariClient.getComponentsCategory(blueprintName, hostGroup);
            for (String component : categories.keySet()) {
                if (categories.get(component).equalsIgnoreCase(MASTER_CATEGORY)) {
                    throw new BadRequestException(
                            String.format("Cannot downscale the '%s' hostGroupAdjustment group, because it contains a '%s' component", hostGroup, component));
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Cannot check the host components category", e);
        }
    }

    private void validateUnregisteredHosts(Stack stack, int sumScalingAdjustments) {
        Set<InstanceMetaData> unregisteredHosts = instanceMetadataRepository.findUnregisteredHostsInStack(stack.getId());
        if (unregisteredHosts.size() == 0) {
            throw new BadRequestException(String.format(
                    "There are no unregistered hosts in stack '%s'. Add some additional nodes to the stack before adding new hosts to the cluster.",
                    stack.getId()));
        }
        if (unregisteredHosts.size() < sumScalingAdjustments) {
            throw new BadRequestException(String.format("Number of unregistered hosts in the stack is %s, but %s would be needed to complete the request.",
                    unregisteredHosts.size(), sumScalingAdjustments));
        }
    }

    private void validateRegisteredHosts(Stack stack, HostGroupAdjustmentJson hostGroupAdjustment) {
        Set<HostMetadata> hostMetadata = hostMetadataRepository.findHostsInHostgroup(hostGroupAdjustment.getHostGroup(), stack.getCluster().getId());
        if (hostMetadata.size() <= -1 * hostGroupAdjustment.getScalingAdjustment()) {
            String errorMessage = String.format("[hostGroup: '%s', current hosts: %s, decommissions requested: %s]",
                    hostGroupAdjustment.getHostGroup(), hostMetadata.size(), -1 * hostGroupAdjustment.getScalingAdjustment());
            throw new BadRequestException(String.format(
                    "The host group must contain at least 1 host after the decommission: %s",
                    errorMessage));
        }
    }

    private void validateHostGroup(Stack stack, HostGroupAdjustmentJson hostGroupAdjustment) {
        if (!assignableHostGroup(stack.getCluster(), hostGroupAdjustment.getHostGroup())) {
            throw new BadRequestException(String.format(
                    "Invalid host group: blueprint '%s' that was used to create the cluster does not contain a host group with this name.",
                    stack.getCluster().getBlueprint().getId(), hostGroupAdjustment.getHostGroup()));
        }
    }

    private Boolean assignableHostGroup(Cluster cluster, String hostGroup) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root;
            root = mapper.readTree(cluster.getBlueprint().getBlueprintText());
            Iterator<JsonNode> hostGroupsIterator = root.path("host_groups").elements();
            while (hostGroupsIterator.hasNext()) {
                JsonNode hostGroupNode = hostGroupsIterator.next();
                if (hostGroupNode.path("name").asText().equals(hostGroup)) {
                    return true;
                }
            }
        } catch (IOException e) {
            throw new InternalServerException("Unhandled exception occurred while reading blueprint: " + e.getMessage(), e);
        }
        return false;
    }

    private void verifyNodeCount(Cluster cluster, int replication, int scalingAdjustment, List<HostMetadata> filteredHostList) {
        MDCBuilder.buildMdcContext(cluster);
        int adjustment = Math.abs(scalingAdjustment);
        int hostSize = filteredHostList.size();
        if (hostSize - adjustment <= replication || hostSize < adjustment) {
            LOGGER.info("Cannot downscale: replication: {}, adjustment: {}, filtered host size: {}", replication, scalingAdjustment, hostSize);
            throw new BadRequestException("There is not enough node to downscale. "
                    + "Check the replication factor and the ApplicationMaster occupation.");
        }
    }

    private boolean doesHostGroupContainDataNode(AmbariClient client, String blueprint, String hostGroup) {
        return client.getBlueprintMap(blueprint).get(hostGroup).contains(DATANODE);
    }

    private List<HostMetadata> checkAndSortByAvailableSpace(Stack stack, AmbariClient client, int replication,
            int adjustment, List<HostMetadata> filteredHostList) {
        MDCBuilder.buildMdcContext(stack.getCluster());
        int removeCount = Math.abs(adjustment);
        Map<String, Map<Long, Long>> dfsSpace = client.getDFSSpace();
        Map<String, Long> sortedAscending = sortByUsedSpace(dfsSpace, false);
        Map<String, Long> selectedNodes = selectNodes(stack, sortedAscending, filteredHostList, removeCount);
        Map<String, Long> remainingNodes = removeSelected(sortedAscending, selectedNodes);
        LOGGER.info("Selected nodes for decommission: {}", selectedNodes);
        LOGGER.info("Remaining nodes after decommission: {}", remainingNodes);
        long usedSpace = getSelectedUsage(selectedNodes);
        long remainingSpace = getRemainingSpace(remainingNodes, dfsSpace);
        long safetyUsedSpace = ((Double) (usedSpace * replication * SAFETY_PERCENTAGE)).longValue();
        LOGGER.info("Checking DFS space for decommission, usedSpace: {}, remainingSpace: {}", usedSpace, remainingSpace);
        LOGGER.info("Used space with replication: {} and safety space: {} is: {}", replication, SAFETY_PERCENTAGE, safetyUsedSpace);
        if (remainingSpace < safetyUsedSpace) {
            throw new BadRequestException(
                    String.format("Trying to move '%s' bytes worth of data to nodes with '%s' bytes of capacity is not allowed", usedSpace, remainingSpace)
            );
        }
        return convert(selectedNodes, filteredHostList);
    }

    private Map<String, Long> selectNodes(Stack stack, Map<String, Long> sortedAscending, List<HostMetadata> filteredHostList, int removeCount) {
        Map<String, Long> select = new HashMap<>();
        int i = 0;
        long stackId = stack.getId();
        for (String host : sortedAscending.keySet()) {
            if (i < removeCount) {
                for (HostMetadata hostMetadata : filteredHostList) {
                    if (hostMetadata.getHostName().equalsIgnoreCase(host)) {
                        InstanceMetaData instanceMetaData = instanceMetadataRepository.findHostInStack(stackId, host);
                        if (!instanceMetaData.getAmbariServer()) {
                            select.put(host, sortedAscending.get(host));
                            i++;
                        }
                        break;
                    }
                }
            } else {
                break;
            }
        }
        return select;
    }

    private Map<String, Long> removeSelected(Map<String, Long> all, Map<String, Long> selected) {
        Map<String, Long> copy = new HashMap<>(all);
        for (String host : selected.keySet()) {
            Iterator<String> iterator = copy.keySet().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().equalsIgnoreCase(host)) {
                    iterator.remove();
                    break;
                }
            }
        }
        return copy;
    }

    private long getSelectedUsage(Map<String, Long> selected) {
        long usage = 0;
        for (String host : selected.keySet()) {
            usage += selected.get(host);
        }
        return usage;
    }

    private long getRemainingSpace(Map<String, Long> remainingNodes, Map<String, Map<Long, Long>> dfsSpace) {
        long remaining = 0;
        for (String host : remainingNodes.keySet()) {
            Map<Long, Long> space = dfsSpace.get(host);
            remaining += space.keySet().iterator().next();
        }
        return remaining;
    }

    private List<HostMetadata> convert(Map<String, Long> selectedNodes, List<HostMetadata> filteredHostList) {
        List<HostMetadata> result = new ArrayList<>();
        for (String host : selectedNodes.keySet()) {
            for (HostMetadata hostMetadata : filteredHostList) {
                if (hostMetadata.getHostName().equalsIgnoreCase(host)) {
                    result.add(hostMetadata);
                    break;
                }
            }
        }
        return result;
    }

}
