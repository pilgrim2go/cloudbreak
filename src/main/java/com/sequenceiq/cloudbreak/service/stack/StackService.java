package com.sequenceiq.cloudbreak.service.stack;

import java.util.Set;

import com.sequenceiq.cloudbreak.controller.json.HostGroupAdjustmentJson;
import com.sequenceiq.cloudbreak.domain.CbUser;
import com.sequenceiq.cloudbreak.domain.InstanceMetaData;
import com.sequenceiq.cloudbreak.domain.Stack;
import com.sequenceiq.cloudbreak.domain.StackDescription;
import com.sequenceiq.cloudbreak.domain.StatusRequest;

public interface StackService {

    Set<Stack> retrievePrivateStacks(CbUser user);

    Set<Stack> retrieveAccountStacks(CbUser user);

    Stack get(Long id);

    Stack get(String ambariAddress);

    Stack create(CbUser user, Stack stack);

    void delete(Long id);

    Set<InstanceMetaData> getMetaData(String hash);

    StackDescription getStackDescription(Stack stack);

    void updateStatus(Long stackId, StatusRequest status);

    Stack getPrivateStack(String name, CbUser cbUser);

    Stack getPublicStack(String name, CbUser cbUser);

    void delete(String name, CbUser cbUser);

    void updateNodeCount(Long stackId, HostGroupAdjustmentJson hostGroupAdjustmentJson);
}
