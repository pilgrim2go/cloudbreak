package com.sequenceiq.cloudbreak.service.stack.connector.gcc;

import com.google.api.services.compute.Compute;
import com.sequenceiq.cloudbreak.domain.GccCredential;
import com.sequenceiq.cloudbreak.logger.MDCBuilder;
import com.sequenceiq.cloudbreak.service.StatusCheckerTask;
import com.sequenceiq.cloudbreak.service.stack.AddInstancesFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class GccImageCheckerStatus implements StatusCheckerTask<GccImageReadyPollerObject> {

    private static final Logger LOGGER = LoggerFactory.getLogger(GccImageCheckerStatus.class);
    private static final String READY = "READY";

    @Override
    public boolean checkStatus(GccImageReadyPollerObject gccImageReadyPollerObject) {
        MDCBuilder.buildMdcContext(gccImageReadyPollerObject.getStack());
        LOGGER.info("Checking status of Gcc Image '{}'.", gccImageReadyPollerObject.getName());
        GccCredential credential = (GccCredential) gccImageReadyPollerObject.getStack().getCredential();
        try {
            Compute.Images.Get getImages = gccImageReadyPollerObject
                    .getCompute().images()
                    .get(credential.getProjectId(), gccImageReadyPollerObject.getName());
            String status = getImages.execute().getStatus();
            return READY.equals(status) ? true : false;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void handleTimeout(GccImageReadyPollerObject gccImageReadyPollerObject) {
        throw new AddInstancesFailedException(String.format(
                "Something went wrong. Gcc image '%s' did not set up in a reasonable timeframe",
                gccImageReadyPollerObject.getName()));
    }

    @Override
    public String successMessage(GccImageReadyPollerObject gccImageReadyPollerObject) {
        MDCBuilder.buildMdcContext(gccImageReadyPollerObject.getStack());
        return String.format("Gcc image '%s' is ready on '%s' stack",
                gccImageReadyPollerObject.getName(), gccImageReadyPollerObject.getStack().getId());
    }
}
