package com.sequenceiq.cloudbreak.service.stack.connector.gcc;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.google.api.services.compute.Compute;
import com.sequenceiq.cloudbreak.domain.GccCredential;
import com.sequenceiq.cloudbreak.logger.MDCBuilder;
import com.sequenceiq.cloudbreak.service.StatusCheckerTask;
import com.sequenceiq.cloudbreak.service.stack.AddInstancesFailedException;

@Component
public class GccInstanceCheckerStatus implements StatusCheckerTask<GccInstanceReadyPollerObject> {

    private static final Logger LOGGER = LoggerFactory.getLogger(GccInstanceCheckerStatus.class);
    private static final String RUNNING = "RUNNING";

    @Override
    public boolean checkStatus(GccInstanceReadyPollerObject gccInstanceReadyPollerObject) {
        MDCBuilder.buildMdcContext(gccInstanceReadyPollerObject.getStack());
        LOGGER.info("Checking status of Gcc Instance '{}'.", gccInstanceReadyPollerObject.getName());
        GccCredential gccCredential = (GccCredential) gccInstanceReadyPollerObject.getStack().getCredential();
        try {
            Compute.Instances.Get getInstances = gccInstanceReadyPollerObject
                    .getCompute()
                    .instances()
                    .get(gccCredential.getProjectId(), gccInstanceReadyPollerObject.getGccZone().getValue(), gccInstanceReadyPollerObject.getName());
            String status = getInstances.execute().getStatus();
            return RUNNING.equals(status) ? true : false;
        } catch (NullPointerException | IOException e) {
            return false;
        }
    }

    @Override
    public void handleTimeout(GccInstanceReadyPollerObject gccInstanceReadyPollerObject) {
        throw new AddInstancesFailedException(String.format(
                "Something went wrong. Instances in Gcc cloudinstance '%s' not started in a reasonable timeframe on '%s' stack.",
                gccInstanceReadyPollerObject.getName(), gccInstanceReadyPollerObject.getStack().getId()));
    }

    @Override
    public String successMessage(GccInstanceReadyPollerObject gccInstanceReadyPollerObject) {
        MDCBuilder.buildMdcContext(gccInstanceReadyPollerObject.getStack());
        return String.format("Gcc instance '%s' is ready on stack", gccInstanceReadyPollerObject.getName());
    }
}
