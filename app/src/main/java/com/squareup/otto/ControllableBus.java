package com.squareup.otto;

public class ControllableBus extends Bus {

    private boolean postEnabled = true;

    public ControllableBus(ThreadEnforcer enforcer) {
        super(enforcer);
    }

    public void enablePost() {
        postEnabled = true;
    }

    public void disablePost() {
        postEnabled = false;
    }

    @Override
    public void post(Object event) {
        if (!postEnabled) return;

        try {
            super.post(event);
        } catch (IllegalStateException ignored) {
        }
    }
}
