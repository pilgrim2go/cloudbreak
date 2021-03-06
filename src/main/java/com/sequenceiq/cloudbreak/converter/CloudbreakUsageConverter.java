package com.sequenceiq.cloudbreak.converter;

import java.text.SimpleDateFormat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.sequenceiq.cloudbreak.controller.json.CloudbreakUsageJson;
import com.sequenceiq.cloudbreak.domain.CbUser;
import com.sequenceiq.cloudbreak.domain.CloudbreakUsage;
import com.sequenceiq.cloudbreak.service.user.UserDetailsService;
import com.sequenceiq.cloudbreak.service.user.UserFilterField;

@Component
public class CloudbreakUsageConverter extends AbstractConverter<CloudbreakUsageJson, CloudbreakUsage> {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    @Autowired
    private UserDetailsService userDetailsService;

    @Override
    public CloudbreakUsageJson convert(CloudbreakUsage entity) {
        String day = DATE_FORMAT.format(entity.getDay());
        CbUser cbUser = userDetailsService.getDetails(entity.getOwner(), UserFilterField.USERID);

        CloudbreakUsageJson json = new CloudbreakUsageJson();
        json.setOwner(entity.getOwner());
        json.setAccount(entity.getAccount());
        json.setBlueprintName(entity.getBlueprintName());
        json.setBlueprintId(entity.getBlueprintId());
        json.setCloud(entity.getCloud());
        json.setZone(entity.getZone());
        json.setInstanceHours(entity.getInstanceHours());
        json.setMachineType(entity.getMachineType());
        json.setDay(day);
        json.setStackId(entity.getStackId());
        json.setStackStatus(entity.getStackStatus());
        json.setStackName(entity.getStackName());
        json.setUsername(cbUser.getUsername());
        return json;
    }

    @Override
    public CloudbreakUsage convert(CloudbreakUsageJson json) {
        CloudbreakUsage entity = new CloudbreakUsage();
        entity.setOwner(json.getOwner());
        entity.setAccount(json.getAccount());
        entity.setBlueprintName(json.getBlueprintName());
        entity.setBlueprintId(json.getBlueprintId());
        entity.setCloud(json.getCloud());
        entity.setZone(json.getZone());
        entity.setInstanceHours(json.getInstanceHours());
        entity.setMachineType(json.getMachineType());
        entity.setStackId(json.getStackId());
        entity.setStackStatus(json.getStackStatus());
        entity.setStackName(json.getStackName());
        return entity;

    }
}
