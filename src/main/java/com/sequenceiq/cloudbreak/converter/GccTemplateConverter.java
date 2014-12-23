package com.sequenceiq.cloudbreak.converter;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.sequenceiq.cloudbreak.controller.json.TemplateJson;
import com.sequenceiq.cloudbreak.controller.validation.GccTemplateParam;
import com.sequenceiq.cloudbreak.domain.CloudPlatform;
import com.sequenceiq.cloudbreak.domain.GccTemplate;
import com.sequenceiq.cloudbreak.service.stack.connector.gcc.domain.GccInstanceType;
import com.sequenceiq.cloudbreak.service.stack.connector.gcc.domain.GccRawDiskType;

@Component
public class GccTemplateConverter extends AbstractConverter<TemplateJson, GccTemplate> {

    @Override
    public TemplateJson convert(GccTemplate entity) {
        TemplateJson gccTemplateJson = new TemplateJson();
        gccTemplateJson.setName(entity.getName());
        gccTemplateJson.setCloudPlatform(CloudPlatform.GCC);
        gccTemplateJson.setId(entity.getId());
        gccTemplateJson.setVolumeCount(entity.getVolumeCount());
        gccTemplateJson.setVolumeSize(entity.getVolumeSize());
        gccTemplateJson.setDescription(entity.getDescription());
        Map<String, Object> props = new HashMap<>();
        putProperty(props, GccTemplateParam.INSTANCETYPE.getName(), entity.getGccInstanceType());
        putProperty(props, GccTemplateParam.TYPE.getName(), entity.getGccRawDiskType());
        gccTemplateJson.setParameters(props);
        gccTemplateJson.setDescription(entity.getDescription() == null ? "" : entity.getDescription());
        return gccTemplateJson;
    }

    @Override
    public GccTemplate convert(TemplateJson json) {
        GccTemplate gccTemplate = new GccTemplate();
        gccTemplate.setName(json.getName());
        gccTemplate.setDescription(json.getDescription());
        gccTemplate.setVolumeCount((json.getVolumeCount() == null) ? 0 : json.getVolumeCount());
        gccTemplate.setVolumeSize((json.getVolumeSize() == null) ? 0 : json.getVolumeSize());
        gccTemplate.setGccInstanceType(GccInstanceType.valueOf(json.getParameters().get(GccTemplateParam.INSTANCETYPE.getName()).toString()));
        Object type = json.getParameters().get(GccTemplateParam.TYPE.getName());
        if (type != null) {
            gccTemplate.setGccRawDiskType(GccRawDiskType.valueOf(type.toString()));
        }
        gccTemplate.setDescription(json.getDescription());
        return gccTemplate;
    }

    private void putProperty(Map<String, Object> props, String key, Object property) {
        if (property != null) {
            props.put(key, property.toString());
        }
    }

}
