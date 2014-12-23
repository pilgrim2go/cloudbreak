package com.sequenceiq.cloudbreak.service.stack.event;

import java.util.Set;

import com.sequenceiq.cloudbreak.domain.CloudPlatform;
import com.sequenceiq.cloudbreak.domain.Resource;

public class AddInstancesComplete extends ProvisionEvent {

    private Set<Resource> resources;
    private String hostGroup;

    public AddInstancesComplete(CloudPlatform cloudPlatform, Long stackId, Set<Resource> resources, String hostGroup) {
        super(cloudPlatform, stackId);
        this.resources = resources;
        this.hostGroup = hostGroup;
    }

    public Set<Resource> getResources() {
        return resources;
    }

    public void setResources(Set<Resource> resources) {
        this.resources = resources;
    }

    public String getHostGroup() {
        return hostGroup;
    }

    public void setHostGroup(String hostGroup) {
        this.hostGroup = hostGroup;
    }
}
