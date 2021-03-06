package com.sequenceiq.cloudbreak.service.stack.resource;

import java.util.List;

import com.google.common.base.Optional;
import com.sequenceiq.cloudbreak.domain.CloudPlatform;
import com.sequenceiq.cloudbreak.domain.Resource;
import com.sequenceiq.cloudbreak.domain.ResourceType;

public interface ResourceBuilder<P extends ProvisionContextObject, D extends DeleteContextObject, DCO extends DescribeContextObject,
        SSCO extends StartStopContextObject> {

    List<Resource> create(P po, int index, List<Resource> resources) throws Exception;

    Boolean delete(Resource resource, D d) throws Exception;

    Boolean rollback(Resource resource, D d) throws Exception;

    Optional<String> describe(Resource resource, DCO dco) throws Exception;

    ResourceBuilderType resourceBuilderType();

    Boolean start(SSCO ssco, Resource resource);

    Boolean stop(SSCO ssco, Resource resource);

    ResourceType resourceType();

    CloudPlatform cloudPlatform();
}
