package com.sequenceiq.cloudbreak.service.stack.resource;

import java.util.List;

import com.google.common.base.Optional;
import com.sequenceiq.cloudbreak.domain.CloudPlatform;
import com.sequenceiq.cloudbreak.domain.Resource;
import com.sequenceiq.cloudbreak.domain.ResourceType;
import com.sequenceiq.cloudbreak.domain.TemplateGroup;

public interface ResourceBuilder<P extends ProvisionContextObject, D extends DeleteContextObject, DCO extends DescribeContextObject,
        SSCO extends StartStopContextObject> {

    List<Resource> create(P po, int index, List<Resource> resources, TemplateGroup templateGroup, String region) throws Exception;

    Boolean delete(Resource resource, D d, String region) throws Exception;

    Boolean rollback(Resource resource, D d, String region) throws Exception;

    Optional<String> describe(Resource resource, DCO dco, String region) throws Exception;

    ResourceBuilderType resourceBuilderType();

    Boolean start(SSCO ssco, Resource resource, String region);

    Boolean stop(SSCO ssco, Resource resource, String region);

    ResourceType resourceType();

    CloudPlatform cloudPlatform();
}
