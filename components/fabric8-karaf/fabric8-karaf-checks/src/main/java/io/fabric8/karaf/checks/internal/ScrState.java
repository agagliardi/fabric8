package io.fabric8.karaf.checks.internal;

import java.util.Collection;

import io.fabric8.karaf.checks.Check;
import org.osgi.framework.Bundle;
import org.osgi.service.component.runtime.ServiceComponentRuntime;
import org.osgi.service.component.runtime.dto.ComponentConfigurationDTO;
import org.osgi.service.component.runtime.dto.ComponentDescriptionDTO;
import org.osgi.util.tracker.ServiceTracker;

public class ScrState extends AbstractBundleChecker {

    private final ServiceTracker<ServiceComponentRuntime, ServiceComponentRuntime> tracker;

    public ScrState() {
        super();
        tracker = new ServiceTracker<>(bundleContext, ServiceComponentRuntime.class, null);
        tracker.open();
    }

    @Override
    protected Check checkBundle(Bundle bundle) {
        if (bundle.getHeaders().get("Service-Component") == null) {
            return null;
        }
        ServiceComponentRuntime svc = tracker.getService();
        if (svc == null) {
            return new Check("scr-state", "No ScrService found");
        }
        Collection<ComponentDescriptionDTO> components = svc.getComponentDescriptionDTOs(bundle);
        if (components != null) {
            for (ComponentDescriptionDTO component : components) {
                Collection<ComponentConfigurationDTO> dtos = svc.getComponentConfigurationDTOs(component);
                for (ComponentConfigurationDTO dto : dtos) {
                    int state = dto.state;
                    if (state != ComponentConfigurationDTO.ACTIVE && state != ComponentConfigurationDTO.SATISFIED) {
                        return new Check("scr-state", "SCR bundle " + bundle.getBundleId() + " is in state " + getState(state));
                    }
                }
            }
        }
        return null;
    }

    private String getState(int state) {
        switch (state) {
            case (-1):
                return "disabled";
            case (ComponentConfigurationDTO.ACTIVE):
                return "active";
            case (ComponentConfigurationDTO.FAILED_ACTIVATION):
                return "activation failed";
            case (ComponentConfigurationDTO.SATISFIED):
                // 112.5.4: Delayed Component
                // [...] the activation of a delayed component configuration does not occur until there is an actual re-
                // quest for a service object.
                // A component is a delayed component when it specifies a service but it is
                // not a factory component and does not have the immediate attribute of the
                // component element set to true.
                return "satisfied";
            case (ComponentConfigurationDTO.UNSATISFIED_CONFIGURATION):
                return "unsatisfied configuration";
            case (ComponentConfigurationDTO.UNSATISFIED_REFERENCE):
                return "unsatisfied reference";
            default:
                return "unknown: " + state;
        }
    }

}
