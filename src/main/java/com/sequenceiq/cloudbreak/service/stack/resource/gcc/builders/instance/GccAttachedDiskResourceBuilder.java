package com.sequenceiq.cloudbreak.service.stack.resource.gcc.builders.instance;

import static com.sequenceiq.cloudbreak.service.stack.connector.azure.AzureStackUtil.ERROR;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.compute.Compute;
import com.google.api.services.compute.model.Disk;
import com.google.api.services.compute.model.Operation;
import com.google.common.base.Optional;
import com.sequenceiq.cloudbreak.controller.InternalServerException;
import com.sequenceiq.cloudbreak.controller.json.JsonHelper;
import com.sequenceiq.cloudbreak.domain.GccCredential;
import com.sequenceiq.cloudbreak.domain.GccTemplate;
import com.sequenceiq.cloudbreak.domain.Resource;
import com.sequenceiq.cloudbreak.domain.ResourceType;
import com.sequenceiq.cloudbreak.domain.Stack;
import com.sequenceiq.cloudbreak.repository.StackRepository;
import com.sequenceiq.cloudbreak.service.PollingService;
import com.sequenceiq.cloudbreak.service.stack.connector.gcc.GccResourceCheckerStatus;
import com.sequenceiq.cloudbreak.service.stack.connector.gcc.GccResourceCreationException;
import com.sequenceiq.cloudbreak.service.stack.connector.gcc.GccResourceReadyPollerObject;
import com.sequenceiq.cloudbreak.service.stack.connector.gcc.GccRemoveCheckerStatus;
import com.sequenceiq.cloudbreak.service.stack.connector.gcc.GccRemoveReadyPollerObject;
import com.sequenceiq.cloudbreak.service.stack.resource.gcc.GccSimpleInstanceResourceBuilder;
import com.sequenceiq.cloudbreak.service.stack.resource.gcc.model.GccDeleteContextObject;
import com.sequenceiq.cloudbreak.service.stack.resource.gcc.model.GccDescribeContextObject;
import com.sequenceiq.cloudbreak.service.stack.resource.gcc.model.GccProvisionContextObject;

@Component
@Order(2)
public class GccAttachedDiskResourceBuilder extends GccSimpleInstanceResourceBuilder {

    @Autowired
    private StackRepository stackRepository;
    @Autowired
    private GccResourceCheckerStatus gccResourceCheckerStatus;
    @Autowired
    private PollingService<GccResourceReadyPollerObject> gccDiskReadyPollerObjectPollingService;
    @Autowired
    private GccRemoveCheckerStatus gccRemoveCheckerStatus;
    @Autowired
    private PollingService<GccRemoveReadyPollerObject> gccRemoveReadyPollerObjectPollingService;
    @Autowired
    private JsonHelper jsonHelper;
    @Autowired
    @Qualifier("intermediateBuilderExecutor")
    private AsyncTaskExecutor intermediateBuilderExecutor;

    @Override
    public List<Resource> create(final GccProvisionContextObject po, int index, List<Resource> resources) throws Exception {
        final Stack stack = stackRepository.findById(po.getStackId());
        final GccTemplate gccTemplate = (GccTemplate) stack.getTemplate();
        final GccCredential gccCredential = (GccCredential) stack.getCredential();
        final List<Resource> resourcesListTemp = new ArrayList<>();
        final String name = String.format("%s-%s-%s", stack.getName(), index, new Date().getTime());

        List<Future<Resource>> futures = new ArrayList<>();
        for (int i = 0; i < gccTemplate.getVolumeCount(); i++) {
            final int indexVolume = i;
            Future<Resource> submit = intermediateBuilderExecutor.submit(new Callable<Resource>() {
                @Override
                public Resource call() throws Exception {
                    String value = name + "-" + indexVolume;
                    Disk disk = new Disk();
                    disk.setSizeGb(gccTemplate.getVolumeSize().longValue());
                    disk.setName(value);
                    disk.setKind(((GccTemplate) stack.getTemplate()).getGccRawDiskType().getUrl(po.getProjectId(), gccTemplate.getGccZone()));
                    Compute.Disks.Insert insDisk = po.getCompute().disks().insert(po.getProjectId(), gccTemplate.getGccZone().getValue(), disk);
                    Operation execute = insDisk.execute();
                    if (execute.getHttpErrorStatusCode() == null) {
                        Compute.ZoneOperations.Get zoneOperations = createZoneOperations(po.getCompute(), gccCredential, gccTemplate, execute);
                        GccResourceReadyPollerObject gccDiskReady = new GccResourceReadyPollerObject(zoneOperations, stack, name, execute.getName());
                        gccDiskReadyPollerObjectPollingService.pollWithTimeout(gccResourceCheckerStatus, gccDiskReady, POLLING_INTERVAL, MAX_POLLING_ATTEMPTS);
                        return new Resource(resourceType(), value, stack);
                    } else {
                        throw new GccResourceCreationException(execute.getHttpErrorMessage(), resourceType(), name);
                    }
                }
            });
            futures.add(submit);
        }
        for (Future<Resource> future : futures) {
            resourcesListTemp.add(future.get());
        }
        return resourcesListTemp;
    }

    @Override
    public Boolean delete(Resource resource, GccDeleteContextObject d) throws Exception {
        Stack stack = stackRepository.findById(d.getStackId());
        try {
            GccTemplate gccTemplate = (GccTemplate) stack.getTemplate();
            GccCredential gccCredential = (GccCredential) stack.getCredential();
            Operation operation = d.getCompute().disks()
                    .delete(gccCredential.getProjectId(), gccTemplate.getGccZone().getValue(), resource.getResourceName()).execute();
            Compute.ZoneOperations.Get zoneOperations = createZoneOperations(d.getCompute(), gccCredential, gccTemplate, operation);
            Compute.GlobalOperations.Get globalOperations = createGlobalOperations(d.getCompute(), gccCredential, gccTemplate, operation);
            GccRemoveReadyPollerObject gccRemoveReady =
                    new GccRemoveReadyPollerObject(zoneOperations, globalOperations, stack, resource.getResourceName(), operation.getName());
            gccRemoveReadyPollerObjectPollingService.pollWithTimeout(gccRemoveCheckerStatus, gccRemoveReady, POLLING_INTERVAL, MAX_POLLING_ATTEMPTS);
        } catch (GoogleJsonResponseException ex) {
            exceptionHandler(ex, resource.getResourceName(), stack);
        } catch (IOException e) {
            throw new InternalServerException(e.getMessage());
        }
        return true;
    }

    @Override
    public Optional<String> describe(Resource resource, GccDescribeContextObject dco) throws Exception {
        Stack stack = stackRepository.findById(dco.getStackId());
        GccTemplate gccTemplate = (GccTemplate) stack.getTemplate();
        GccCredential gccCredential = (GccCredential) stack.getCredential();
        try {
            Compute.Disks.Get getDisk =
                    dco.getCompute().disks().get(gccCredential.getProjectId(), gccTemplate.getGccZone().getValue(), resource.getResourceName());
            return Optional.fromNullable(getDisk.execute().toPrettyString());
        } catch (IOException e) {
            return Optional.fromNullable(jsonHelper.createJsonFromString(String.format("{\"Attached_Disk\": {%s}}", ERROR)).toString());
        }
    }

    @Override
    public ResourceType resourceType() {
        return ResourceType.GCC_ATTACHED_DISK;
    }

}
