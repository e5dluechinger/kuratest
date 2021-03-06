/*******************************************************************************
 * Copyright (c) 2011, 2016 Eurotech and/or its affiliates
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Eurotech
 *     Jens Reimann <jreimann@redhat.com> Fixes and cleanups
 *******************************************************************************/
package org.eclipse.kura.web.server;

import static org.eclipse.kura.configuration.ConfigurationService.KURA_SERVICE_PID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.kura.configuration.ComponentConfiguration;
import org.eclipse.kura.configuration.ConfigurableComponent;
import org.eclipse.kura.configuration.ConfigurationService;
import org.eclipse.kura.configuration.Password;
import org.eclipse.kura.configuration.SelfConfiguringComponent;
import org.eclipse.kura.configuration.metatype.AD;
import org.eclipse.kura.configuration.metatype.Icon;
import org.eclipse.kura.configuration.metatype.OCD;
import org.eclipse.kura.configuration.metatype.Option;
import org.eclipse.kura.web.server.util.KuraExceptionHandler;
import org.eclipse.kura.web.server.util.ServiceLocator;
import org.eclipse.kura.web.shared.GwtKuraException;
import org.eclipse.kura.web.shared.model.GwtConfigComponent;
import org.eclipse.kura.web.shared.model.GwtConfigParameter;
import org.eclipse.kura.web.shared.model.GwtConfigParameter.GwtConfigParameterType;
import org.eclipse.kura.web.shared.model.GwtXSRFToken;
import org.eclipse.kura.web.shared.service.GwtComponentService;
import org.osgi.framework.ServiceReference;

public class GwtComponentServiceImpl extends OsgiRemoteServiceServlet implements GwtComponentService {

    private static final String SERVICE_FACTORY_PID = "service.factoryPid";
    private static final String KURA_UI_SERVICE_HIDE = "kura.ui.service.hide";

    private static final long serialVersionUID = -4176701819112753800L;

    @Override
    public List<GwtConfigComponent> findServicesConfigurations(GwtXSRFToken xsrfToken) throws GwtKuraException {
        checkXSRFToken(xsrfToken);
        List<String> hidePidsList = new ArrayList<String>();

        // identify the services to hide by component configuration property
        fillServicesToHideList(hidePidsList);

        List<GwtConfigComponent> gwtComponentConfigs = new ArrayList<GwtConfigComponent>();
        for (GwtConfigComponent gwtComponentConfig : findFilteredComponentConfigurationsInternal()) {
            String componentPid = gwtComponentConfig.getComponentId();
            if (hidePidsList.contains(componentPid) || componentPid.endsWith("SystemPropertiesService")
                    || componentPid.endsWith("NetworkAdminService")
                    || componentPid.endsWith("NetworkConfigurationService")
                    || componentPid.endsWith("SslManagerService")
                    || componentPid.endsWith("FirewallConfigurationService")) {
                continue;
            }
            gwtComponentConfigs.add(gwtComponentConfig);
        }

        return gwtComponentConfigs;
    }

    @Override
    public List<GwtConfigComponent> findFilteredComponentConfigurations(GwtXSRFToken xsrfToken)
            throws GwtKuraException {
        checkXSRFToken(xsrfToken);
        return findFilteredComponentConfigurationsInternal();
    }

    @Override
    public List<GwtConfigComponent> findFilteredComponentConfiguration(GwtXSRFToken xsrfToken, String componentPid)
            throws GwtKuraException {
        checkXSRFToken(xsrfToken);
        return findFilteredComponentConfigurationInternal(componentPid);
    }

    @Override
    public List<GwtConfigComponent> findComponentConfigurations(GwtXSRFToken xsrfToken) throws GwtKuraException {
        checkXSRFToken(xsrfToken);
        return findComponentConfigurationsInternal();
    }

    @Override
    public List<GwtConfigComponent> findComponentConfiguration(GwtXSRFToken xsrfToken, String componentPid)
            throws GwtKuraException {
        checkXSRFToken(xsrfToken);
        return findComponentConfigurationInternal(componentPid);
    }

    @Override
    public void updateComponentConfiguration(GwtXSRFToken xsrfToken, GwtConfigComponent gwtCompConfig)
            throws GwtKuraException {
        checkXSRFToken(xsrfToken);
        ConfigurationService cs = ServiceLocator.getInstance().getService(ConfigurationService.class);
        try {

            // Build the new properties
            Map<String, Object> properties = new HashMap<String, Object>();
            ComponentConfiguration backupCC = cs.getComponentConfiguration(gwtCompConfig.getComponentId());
            Map<String, Object> backupConfigProp = backupCC.getConfigurationProperties();
            for (GwtConfigParameter gwtConfigParam : gwtCompConfig.getParameters()) {
                Object objValue;

                ComponentConfiguration currentCC = cs.getComponentConfiguration(gwtCompConfig.getComponentId());
                Map<String, Object> currentConfigProp = currentCC.getConfigurationProperties();
                Object currentObjValue = currentConfigProp.get(gwtConfigParam.getId());

                int cardinality = gwtConfigParam.getCardinality();
                if (cardinality == 0 || cardinality == 1 || cardinality == -1) {

                    String strValue = gwtConfigParam.getValue();

                    if (currentObjValue instanceof Password && PLACEHOLDER.equals(strValue)) {
                        objValue = currentConfigProp.get(gwtConfigParam.getId());
                    } else {
                        objValue = getObjectValue(gwtConfigParam, strValue);
                    }
                } else {

                    String[] strValues = gwtConfigParam.getValues();

                    if (currentObjValue instanceof Password[]) {
                        Password[] currentPasswordValue = (Password[]) currentObjValue;
                        for (int i = 0; i < strValues.length; i++) {
                            if (PLACEHOLDER.equals(strValues[i])) {
                                strValues[i] = new String(currentPasswordValue[i].getPassword());
                            }
                        }
                    }

                    objValue = getObjectValue(gwtConfigParam, strValues);
                }
                properties.put(gwtConfigParam.getId(), objValue);
            }

            // Force kura.service.pid into properties, if originally present
            if (backupConfigProp.get(KURA_SERVICE_PID) != null) {
                properties.put(KURA_SERVICE_PID, backupConfigProp.get(KURA_SERVICE_PID));
            }
            //
            // apply them
            cs.updateConfiguration(gwtCompConfig.getComponentId(), properties);
        } catch (Throwable t) {
            KuraExceptionHandler.handle(t);
        }
    }

    private void sortConfigurationsByName(List<ComponentConfiguration> configs) {
        Collections.sort(configs, new Comparator<ComponentConfiguration>() {

            @Override
            public int compare(ComponentConfiguration arg0, ComponentConfiguration arg1) {
                String name0;
                int start = arg0.getPid().lastIndexOf('.');
                int substringIndex = start + 1;
                if (start != -1 && substringIndex < arg0.getPid().length()) {
                    name0 = arg0.getPid().substring(substringIndex);
                } else {
                    name0 = arg0.getPid();
                }

                String name1;
                start = arg1.getPid().lastIndexOf('.');
                substringIndex = start + 1;
                if (start != -1 && substringIndex < arg1.getPid().length()) {
                    name1 = arg1.getPid().substring(substringIndex);
                } else {
                    name1 = arg1.getPid();
                }
                return name0.compareTo(name1);
            }
        });
    }

    private Object getObjectValue(GwtConfigParameter gwtConfigParam, String strValue) {
        Object objValue = null;
        GwtConfigParameterType gwtType = gwtConfigParam.getType();
        if (gwtType == GwtConfigParameterType.STRING) {
            objValue = strValue;
        } else if (strValue != null && !strValue.trim().isEmpty()) {
            switch (gwtType) {
            case LONG:
                objValue = Long.parseLong(strValue);
                break;
            case DOUBLE:
                objValue = Double.parseDouble(strValue);
                break;
            case FLOAT:
                objValue = Float.parseFloat(strValue);
                break;
            case INTEGER:
                objValue = Integer.parseInt(strValue);
                break;
            case SHORT:
                objValue = Short.parseShort(strValue);
                break;
            case BYTE:
                objValue = Byte.parseByte(strValue);
                break;
            case BOOLEAN:
                objValue = Boolean.parseBoolean(strValue);
                break;
            case PASSWORD:
                objValue = new Password(strValue);
                break;
            case CHAR:
                objValue = Character.valueOf(strValue.charAt(0));
                break;
            default:
                break;
            }
        }
        return objValue;
    }

    private Object[] getObjectValue(GwtConfigParameter gwtConfigParam, String[] defaultValues) {
        List<Object> values = new ArrayList<Object>();
        GwtConfigParameterType type = gwtConfigParam.getType();
        switch (type) {
        case BOOLEAN:
            for (String value : defaultValues) {
                if (!value.trim().isEmpty()) {
                    values.add(Boolean.valueOf(value));
                }
            }
            return values.toArray(new Boolean[] {});

        case BYTE:
            for (String value : defaultValues) {
                if (!value.trim().isEmpty()) {
                    values.add(Byte.valueOf(value));
                }
            }
            return values.toArray(new Byte[] {});

        case CHAR:
            for (String value : defaultValues) {
                if (!value.trim().isEmpty()) {
                    values.add(new Character(value.charAt(0)));
                }
            }
            return values.toArray(new Character[] {});

        case DOUBLE:
            for (String value : defaultValues) {
                if (!value.trim().isEmpty()) {
                    values.add(Double.valueOf(value));
                }
            }
            return values.toArray(new Double[] {});

        case FLOAT:
            for (String value : defaultValues) {
                if (!value.trim().isEmpty()) {
                    values.add(Float.valueOf(value));
                }
            }
            return values.toArray(new Float[] {});

        case INTEGER:
            for (String value : defaultValues) {
                if (!value.trim().isEmpty()) {
                    values.add(Integer.valueOf(value));
                }
            }
            return values.toArray(new Integer[] {});

        case LONG:
            for (String value : defaultValues) {
                if (!value.trim().isEmpty()) {
                    values.add(Long.valueOf(value));
                }
            }
            return values.toArray(new Long[] {});

        case SHORT:
            for (String value : defaultValues) {
                if (!value.trim().isEmpty()) {
                    values.add(Short.valueOf(value));
                }
            }
            return values.toArray(new Short[] {});

        case PASSWORD:
            for (String value : defaultValues) {
                if (!value.trim().isEmpty()) {
                    values.add(new Password(value));
                }
            }
            return values.toArray(new Password[] {});

        case STRING:
            for (String value : defaultValues) {
                if (!value.trim().isEmpty()) {
                    values.add(value);
                }
            }
            return values.toArray(new String[] {});
        default:
            return null;
        }
    }

    private String stripPidPrefix(String pid) {
        int start = pid.lastIndexOf('.');
        if (start < 0) {
            return pid;
        } else {
            int begin = start + 1;
            if (begin < pid.length()) {
                return pid.substring(begin);
            } else {
                return pid;
            }
        }
    }

    private void fillServicesToHideList(List<String> hidePidsList) throws GwtKuraException {
        Collection<ServiceReference<ConfigurableComponent>> configurableComponentReferences = ServiceLocator
                .getInstance().getServiceReferences(ConfigurableComponent.class, null);

        Collection<ServiceReference<SelfConfiguringComponent>> selfConfiguringComponentReferences = ServiceLocator
                .getInstance().getServiceReferences(SelfConfiguringComponent.class, null);

        List<ServiceReference<?>> componentReferences = new ArrayList<ServiceReference<?>>();
        componentReferences.addAll(configurableComponentReferences);
        componentReferences.addAll(selfConfiguringComponentReferences);

        for (ServiceReference<?> componentReference : componentReferences) {
            Object propertyObject = componentReference.getProperty(KURA_SERVICE_PID);
            if (componentReference.getProperty(KURA_UI_SERVICE_HIDE) != null && propertyObject != null) {
                String servicePid = (String) propertyObject;
                hidePidsList.add(servicePid);
            }
            ServiceLocator.getInstance().ungetService(componentReference);
        }
    }

    private List<GwtConfigComponent> findFilteredComponentConfigurationsInternal() throws GwtKuraException {
        ConfigurationService cs = ServiceLocator.getInstance().getService(ConfigurationService.class);
        List<GwtConfigComponent> gwtConfigs = new ArrayList<GwtConfigComponent>();
        try {

            List<ComponentConfiguration> configs = cs.getComponentConfigurations();
            // sort the list alphabetically by service name
            sortConfigurationsByName(configs);

            for (ComponentConfiguration config : configs) {
                GwtConfigComponent gwtConfigComponent = createGwtComponentConfiguration(config);
                GwtConfigComponent cleanedGwtConfigComponent = removeNoMetatypeProperties(gwtConfigComponent, config);
                if (cleanedGwtConfigComponent != null) {
                    gwtConfigs.add(cleanedGwtConfigComponent);
                }
            }
        } catch (Throwable t) {
            KuraExceptionHandler.handle(t);
        }
        return gwtConfigs;
    }

    private List<GwtConfigComponent> findComponentConfigurationInternal(String componentPid) throws GwtKuraException {
        ConfigurationService cs = ServiceLocator.getInstance().getService(ConfigurationService.class);
        List<GwtConfigComponent> gwtConfigs = new ArrayList<GwtConfigComponent>();
        try {
            ComponentConfiguration config = cs.getComponentConfiguration(componentPid);

            GwtConfigComponent gwtConfigComponent = createGwtComponentConfiguration(config);
            gwtConfigs.add(gwtConfigComponent);
        } catch (Throwable t) {
            KuraExceptionHandler.handle(t);
        }
        return gwtConfigs;
    }

    private List<GwtConfigComponent> findFilteredComponentConfigurationInternal(String componentPid)
            throws GwtKuraException {
        ConfigurationService cs = ServiceLocator.getInstance().getService(ConfigurationService.class);
        List<GwtConfigComponent> gwtConfigs = new ArrayList<GwtConfigComponent>();
        try {
            ComponentConfiguration config = cs.getComponentConfiguration(componentPid);

            GwtConfigComponent gwtConfigComponent = createGwtComponentConfiguration(config);
            GwtConfigComponent cleanedGwtConfigComponent = removeNoMetatypeProperties(gwtConfigComponent, config);
            if (cleanedGwtConfigComponent != null) {
                gwtConfigs.add(cleanedGwtConfigComponent);
            }
        } catch (Throwable t) {
            KuraExceptionHandler.handle(t);
        }
        return gwtConfigs;
    }

    private GwtConfigComponent removeNoMetatypeProperties(GwtConfigComponent gwtConfig, ComponentConfiguration config) {
        GwtConfigComponent cleanedConfigComponent = null;
        OCD ocd = config.getDefinition();
        if (ocd != null && gwtConfig != null) {
            cleanedConfigComponent = new GwtConfigComponent();

            cleanedConfigComponent.setComponentDescription(gwtConfig.getComponentDescription());
            cleanedConfigComponent.setComponentId(gwtConfig.getComponentId());
            cleanedConfigComponent.setComponentIcon(gwtConfig.getComponentIcon());
            cleanedConfigComponent.setComponentName(gwtConfig.getComponentName());
            cleanedConfigComponent.setProperties(gwtConfig.getProperties());

            List<GwtConfigParameter> cleanedConfigParameters = new ArrayList<GwtConfigParameter>();

            for (AD ad : ocd.getAD()) {
                GwtConfigParameter parameter = gwtConfig.getParameter(ad.getId());
                if (parameter != null) {
                    cleanedConfigParameters.add(parameter);
                }
            }
            cleanedConfigComponent.setParameters(cleanedConfigParameters);
        }
        return cleanedConfigComponent;
    }

    private List<GwtConfigComponent> findComponentConfigurationsInternal() throws GwtKuraException {
        ConfigurationService cs = ServiceLocator.getInstance().getService(ConfigurationService.class);
        List<GwtConfigComponent> gwtConfigs = new ArrayList<GwtConfigComponent>();
        try {

            List<ComponentConfiguration> configs = cs.getComponentConfigurations();
            // sort the list alphabetically by service name
            sortConfigurationsByName(configs);

            for (ComponentConfiguration config : configs) {
                GwtConfigComponent gwtConfigComponent = createGwtComponentConfiguration(config);
                gwtConfigs.add(gwtConfigComponent);
            }
        } catch (Throwable t) {
            KuraExceptionHandler.handle(t);
        }
        return gwtConfigs;
    }

    private GwtConfigComponent createGwtComponentConfiguration(ComponentConfiguration config) throws GwtKuraException {
        GwtConfigComponent gwtConfig = null;

        OCD ocd = config.getDefinition();
        if (ocd != null) {

            gwtConfig = new GwtConfigComponent();
            gwtConfig.setComponentId(config.getPid());

            Map<String, Object> props = config.getConfigurationProperties();
            if (props != null && props.get(SERVICE_FACTORY_PID) != null) {
                String pid = stripPidPrefix(config.getPid());
                gwtConfig.setComponentName(pid);
            } else {
                gwtConfig.setComponentName(ocd.getName());
            }

            gwtConfig.setComponentDescription(ocd.getDescription());
            if (ocd.getIcon() != null && !ocd.getIcon().isEmpty()) {
                Icon icon = ocd.getIcon().get(0);
                gwtConfig.setComponentIcon(icon.getResource());
            }

            List<GwtConfigParameter> gwtParams = new ArrayList<GwtConfigParameter>();
            gwtConfig.setParameters(gwtParams);

            if (config.getConfigurationProperties() != null) {
                List<GwtConfigParameter> metatypeProps = getADProperties(config);
                gwtParams.addAll(metatypeProps);

                List<String> addedIds = new ArrayList<String>();
                for (GwtConfigParameter gwtParam : gwtParams) {
                    addedIds.add(gwtParam.getId());
                }

                List<GwtConfigParameter> nonMetatypeProps = getNonMetatypeProperties(config, addedIds);
                gwtParams.addAll(nonMetatypeProps);
            }
        }
        return gwtConfig;
    }

    private List<GwtConfigParameter> getNonMetatypeProperties(ComponentConfiguration config, List<String> addedIds) {
        List<GwtConfigParameter> gwtParams = new ArrayList<GwtConfigParameter>();
        for (Map.Entry<String, Object> entry : config.getConfigurationProperties().entrySet()) {
            if (!addedIds.contains(entry.getKey())) {
                GwtConfigParameter gwtParam = new GwtConfigParameter();
                gwtParam.setId(entry.getKey());
                Object value = entry.getValue();

                // this could be an array value
                if (value != null && value instanceof Object[]) {
                    Object[] objValues = (Object[]) value;
                    List<String> strValues = new ArrayList<String>();
                    for (Object v : objValues) {
                        if (v != null) {
                            strValues.add(String.valueOf(v));
                        }
                    }
                    gwtParam.setValues(strValues.toArray(new String[] {}));
                } else if (value != null) {
                    gwtParam.setValue(String.valueOf(value));
                }

                gwtParams.add(gwtParam);
            }

        }
        return gwtParams;
    }

    private List<GwtConfigParameter> getADProperties(ComponentConfiguration config) {
        List<GwtConfigParameter> gwtParams = new ArrayList<GwtConfigParameter>();
        OCD ocd = config.getDefinition();
        for (AD ad : ocd.getAD()) {
            GwtConfigParameter gwtParam = new GwtConfigParameter();
            gwtParam.setId(ad.getId());
            gwtParam.setName(ad.getName());
            gwtParam.setDescription(ad.getDescription());
            gwtParam.setType(GwtConfigParameterType.valueOf(ad.getType().name()));
            gwtParam.setRequired(ad.isRequired());
            gwtParam.setCardinality(ad.getCardinality());
            if (ad.getOption() != null && !ad.getOption().isEmpty()) {
                Map<String, String> options = new HashMap<String, String>();
                for (Option option : ad.getOption()) {
                    options.put(option.getLabel(), option.getValue());
                }
                gwtParam.setOptions(options);
            }
            gwtParam.setMin(ad.getMin());
            gwtParam.setMax(ad.getMax());

            // handle the value based on the cardinality of the attribute
            int cardinality = ad.getCardinality();
            Object value = config.getConfigurationProperties().get(ad.getId());
            if (value != null) {
                if (cardinality == 0 || cardinality == 1 || cardinality == -1) {
                    if (gwtParam.getType().equals(GwtConfigParameterType.PASSWORD)) {
                        gwtParam.setValue(PLACEHOLDER);
                    } else {
                        gwtParam.setValue(String.valueOf(value));
                    }
                } else {
                    // this could be an array value
                    if (value instanceof Object[]) {
                        Object[] objValues = (Object[]) value;
                        List<String> strValues = new ArrayList<String>();
                        for (Object v : objValues) {
                            if (v != null) {
                                if (gwtParam.getType().equals(GwtConfigParameterType.PASSWORD)) {
                                    strValues.add(PLACEHOLDER);
                                } else {
                                    strValues.add(String.valueOf(v));
                                }
                            }
                        }
                        gwtParam.setValues(strValues.toArray(new String[] {}));
                    }
                }
            }
            gwtParams.add(gwtParam);
        }
        return gwtParams;
    }
}