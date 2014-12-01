package com.sequenceiq.cloudbreak.service.stack.connector.gcc;

import com.google.api.services.compute.Compute;
import com.sequenceiq.cloudbreak.domain.GccTemplate;
import com.sequenceiq.cloudbreak.domain.Stack;
import com.sequenceiq.cloudbreak.service.stack.connector.gcc.domain.GccZone;

public class GccImageReadyPollerObject {

    private String name;
    private Stack stack;
    private Compute compute;
    private GccTemplate template;
    private GccZone gccZone;

    public GccImageReadyPollerObject(Compute compute, Stack stack, String name, GccTemplate template, GccZone gccZone) {
        this.compute = compute;
        this.stack = stack;
        this.name = name;
        this.template = template;
        this.gccZone = gccZone;
    }

    public GccTemplate getTemplate() {
        return template;
    }

    public void setTemplate(GccTemplate template) {
        this.template = template;
    }

    public GccZone getGccZone() {
        return gccZone;
    }

    public void setGccZone(GccZone gccZone) {
        this.gccZone = gccZone;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Stack getStack() {
        return stack;
    }

    public void setStack(Stack stack) {
        this.stack = stack;
    }

    public Compute getCompute() {
        return compute;
    }

    public void setCompute(Compute compute) {
        this.compute = compute;
    }
}
