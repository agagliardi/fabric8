/**
 *  Copyright 2005-2015 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.cxf.registry;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import javax.management.BadAttributeValueExpException;
import javax.management.BadBinaryOpValueExpException;
import javax.management.BadStringOperationException;
import javax.management.InvalidApplicationException;
import javax.management.MBeanServer;
import javax.management.MBeanServerDelegate;
import javax.management.MBeanServerNotification;
import javax.management.MalformedObjectNameException;
import javax.management.Notification;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.QueryExp;

import io.fabric8.api.Container;
import io.fabric8.api.FabricService;
import io.fabric8.api.Version;
import io.fabric8.api.jcip.ThreadSafe;
import io.fabric8.api.scr.AbstractComponent;
import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.common.util.Closeables;
import io.fabric8.common.util.PublicPortMapper;
import io.fabric8.common.util.Strings;
import io.fabric8.internal.JsonHelper;
import io.fabric8.zookeeper.ZkPath;
import io.fabric8.zookeeper.utils.ZooKeeperUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.NodeCacheExtended;
import org.apache.curator.framework.recipes.cache.NodeCacheExtendedListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.zookeeper.CreateMode;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ThreadSafe
@Component(name = "io.fabric8.cxf.registry", label = "Fabric8 CXF Registration Handler", immediate = true, metatype = false)
@Service({ ConnectionStateListener.class })
public final class FabricCxfRegistrationHandler extends AbstractComponent implements ConnectionStateListener, NodeCacheExtendedListener {

    public static final String CXF_API_ENDPOINT_MBEAN_NAME = "io.fabric8.cxf:*";
    private static final ObjectName CXF_OBJECT_NAME =  objectNameFor(CXF_API_ENDPOINT_MBEAN_NAME);

    private NodeCacheExtended versionNodeMonitor;

    private static final Logger LOGGER = LoggerFactory.getLogger(FabricCxfRegistrationHandler.class);
    private static final Object[] EMPTY_PARAMS = {};
    private static final String[] EMPTY_SIGNATURE = {};

    @Reference(referenceInterface = FabricService.class)
    private final ValidatingReference<FabricService> fabricService = new ValidatingReference<FabricService>();
    @Reference(referenceInterface = CuratorFramework.class)
    private final ValidatingReference<CuratorFramework> curator = new ValidatingReference<CuratorFramework>();
    @Reference(referenceInterface = ConfigurationAdmin.class, cardinality = ReferenceCardinality.OPTIONAL_UNARY)
    private ConfigurationAdmin configAdmin;

    private Set<String> registeredZkPaths = new ConcurrentSkipListSet<String>();
    private Map<String, String> registeredUrls = new ConcurrentHashMap<String, String>();

    private NotificationListener listener = new NotificationListener() {
        @Override
        public void handleNotification(Notification notification, Object handback) {
            if (notification instanceof MBeanServerNotification) {
                MBeanServerNotification mBeanServerNotification = (MBeanServerNotification) notification;
                ObjectName mBeanName = mBeanServerNotification.getMBeanName();
                String type = mBeanServerNotification.getType();
                Container currentContainer = getCurrentContainer();
                if (currentContainer != null) {
                    onMBeanEvent(currentContainer, mBeanName, type);
                }
            }
        }
    };

    private NotificationFilter filter = new NotificationFilter() {
        @Override
        public boolean isNotificationEnabled(Notification notification) {
            return (notification instanceof MBeanServerNotification) &&
                    CXF_OBJECT_NAME.apply(((MBeanServerNotification) notification).getMBeanName());
        }
    };

    private QueryExp isCxfServiceEndpointQuery = new QueryExp() {
        @Override
        public boolean apply(ObjectName name) throws BadStringOperationException, BadBinaryOpValueExpException, BadAttributeValueExpException, InvalidApplicationException {
            String type = name.getKeyProperty("type");
            return type != null && "Bus.Service.Endpoint".equals(type);
        }

        @Override
        public void setMBeanServer(MBeanServer s) {
        }
    };

    private MBeanServer mBeanServer;
    private boolean registeredListener;

    @Activate
    void activate() throws Exception {
        activateComponent();

        if (mBeanServer == null) {
            mBeanServer = ManagementFactory.getPlatformMBeanServer();
        }

        if (mBeanServer != null) {
            Object handback = null;
            mBeanServer.addNotificationListener(MBeanServerDelegate.DELEGATE_NAME, listener, filter, handback);
            this.registeredListener = true;
        }

        String id = getCurrentContainer().getId();
        String zkPath = ZkPath.CONFIG_CONTAINER.getPath(id);
        versionNodeMonitor = new NodeCacheExtended(curator.get(), zkPath);
        versionNodeMonitor.getListenable().addListener(this);
        versionNodeMonitor.start();

        replay();
    }

    @Deactivate
    void deactivate() throws Exception {
        if (registeredListener && mBeanServer != null) {
            mBeanServer.removeNotificationListener(MBeanServerDelegate.DELEGATE_NAME, listener);
        }

        // lets remove all the previously generated paths
        List<String> paths = new ArrayList<String>(registeredZkPaths);
        for (String path : paths) {
            removeZkPath(path);
        }
        deactivateComponent();

        versionNodeMonitor.getListenable().removeListener(this);
        Closeables.closeQuietly(versionNodeMonitor);
        versionNodeMonitor = null;
    }

    @Override
    public void stateChanged(CuratorFramework client, ConnectionState newState) {
        if (isValid()) {
            switch (newState) {
                case CONNECTED:
                case RECONNECTED:
                    replay();
            }
        }
    }


    /**
     * Replays again all events.
     */
    protected void replay() {
        // query all the mbeans and check they are all registered for the current container...
        if (mBeanServer != null) {
            Container container = getCurrentContainer();
            if (container != null) {
                Set<ObjectName> objectNames = getObjectNames();
                if (container != null) {
                    for (ObjectName oName : objectNames) {
                        String type = null;
                        onMBeanEvent(container, oName, type);
                    }
                }
            }
        }
    }

    protected Set<ObjectName> getObjectNames(){
        Set<ObjectName> result = new HashSet<>();

        ObjectName objectName = createObjectName(CXF_API_ENDPOINT_MBEAN_NAME);
        Set<ObjectInstance> instances = mBeanServer.queryMBeans(objectName, isCxfServiceEndpointQuery);
        for(ObjectInstance instance : instances){
            result.add(instance.getObjectName());
        }
        return result;
    }

    protected Container getCurrentContainer() {
        try {
            return fabricService.get().getCurrentContainer();
        } catch (Exception e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Ignoring exception due to current container not being available " + e, e);
            }
            return null;
        }
    }

    protected void onMBeanEvent(Container container, ObjectName oName, String type) {
        try {

            if (isCxfServiceEndpointQuery.apply(oName)) {
                if(MBeanServerNotification.UNREGISTRATION_NOTIFICATION.equals(type)){
                    unregisterApiEndpoint(container, oName);
                }else{
                    Object state = mBeanServer.getAttribute(oName, "State");
                    String address = getAddress(oName, type, state);
                    if(mBeanServer.isRegistered(oName)){
                        boolean started = state instanceof String && state.toString().toUpperCase().startsWith("START");
                        boolean created = state instanceof String && state.toString().toUpperCase().startsWith("CREATE");

                        if (address != null && (started || created)) {
                            LOGGER.info("Registering endpoint " + oName + " type " + type + " has status " + state + "at " + address);
                            registerApiEndpoint(container, oName, address, started);
                        } else {
                            if (address == null) {
                                LOGGER.warn("Endpoint " + oName + " type " + type + " has status " + state + "but no address");
                            } else {
                                LOGGER.info("Unregistering endpoint " + oName + " type " + type + " has status " + state + "at " + address);
                            }
                            unregisterApiEndpoint(container, oName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to process " + oName + ". " + e, e);
        }
    }

    private String getAddress(ObjectName oName, String type, Object state) {
        String address = null;
        try {
            Object addressValue = mBeanServer.getAttribute(oName, "Address");
            if (addressValue instanceof String) {
                address = addressValue.toString();
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to get address for endpoint " + oName + " type " + type + " has status " + state + ". " + e, e);
        }
        return address;
    }

    public static ObjectName createObjectName(String name) {
        ObjectName objectName = null;
        try {
            objectName = new ObjectName(name);
        } catch (MalformedObjectNameException e) {
            LOGGER.error("Failed to create ObjectName for " + name + ". " + e, e);
        }
        return objectName;
    }

    protected void registerApiEndpoint(Container container, ObjectName oName, String address, boolean started) {
        String actualEndpointUrl = null;

        try {
            String url;
            String id = container.getId();

            if (isFullAddress(address)) {
                url = toPublicAddress(id, address);
            } else {
                String cxfBus = getCxfServletPath(oName);
                url = "${zk:" + id + "/http}" + cxfBus + address;
            }

            actualEndpointUrl = ZooKeeperUtils.getSubstitutedData(curator.get(), url);

            // lets assume these locations are hard coded
            // may be nice to discover from JMX one day
            String apiDocPath = "/api-docs";
            String wsdlPath = "?wsdl";
            String wadlPath = "?_wadl";

            Version version = container.getVersion();
            String versionId = version != null ? version.getId() : null;

            String json = "{\"id\":" + JsonHelper.jsonEncodeString(id)
                    + ", \"container\":" + JsonHelper.jsonEncodeString(id)
                    + ", \"version\":" + JsonHelper.jsonEncodeString(versionId)
                    + ", \"services\":[" + JsonHelper.jsonEncodeString(url) + "]" +
                      ", \"objectName\":" + JsonHelper.jsonEncodeString(oName.toString()) + "";
            boolean rest = false;
            if (booleanAttribute(oName, "isWADL")) {
                rest = true;
                json += ", \"wadl\":" + JsonHelper.jsonEncodeString(wadlPath);
            }
            if (booleanAttribute(oName, "isSwagger")) {
                rest = true;
                json += ", \"apidocs\":" + JsonHelper.jsonEncodeString(apiDocPath);
            }
            if (booleanAttribute(oName, "isWSDL")) {
                json += ", \"wsdl\":" + JsonHelper.jsonEncodeString(wsdlPath);
            }
            json += "}";

            String path = getPath(container, oName, address, rest);
            LOGGER.info("Registered CXF API at " + path + " JSON: " + json);
            if (!started && !rest) {
                LOGGER.warn("Since the CXF service isn't started, this could really be a REST endpoint rather than WSDL at " + path);
            }
            registeredZkPaths.add(path);
            registeredUrls.put(oName.toString(), address);
            ZooKeeperUtils.setData(curator.get(), path, json, CreateMode.EPHEMERAL);
        } catch (Exception e) {
            LOGGER.error("Failed to register API endpoint for {}.", actualEndpointUrl, e);
        }
    }

    private String toPublicAddress(String container, String address) {
        try {
            URI uri = new URI(address);
            int _port = uri.getPort();
            if (_port == -1) {
                // ENTESB-6754 - new URI("HOSTNAME_WITHOUT_DOTS").getPort() returns -1. It works well with URL
                // instead of URI...
                try {
                    _port = new URL(address).getPort();
                } catch (MalformedURLException ignored) {
                }
            }
            if (_port == -1) {
                // try with scheme matching
                if ("http".equals(uri.getScheme())) {
                    _port = 80;
                } else if ("https".equals(uri.getScheme())) {
                    _port = 443;
                }
            }
            int port = PublicPortMapper.getPublicPort(_port);
            String hostname = "${zk:" + container + "/ip}";
            String path = uri.getPath();
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            if (port == -1) {
                LOGGER.warn("Can't determine port number from endpoint address: " + address + ". Creating public address without port - please verify endpoint configuration.");
                return uri.getScheme() + "://" + hostname + "/" + path;
            }
            return uri.getScheme() + "://" + hostname + ":" + port + "/" + path;
        } catch (URISyntaxException e) {
            LOGGER.warn("Could not map URL to a public address: " + address);
            return address;
        }
    }

    protected boolean booleanAttribute(ObjectName oName, String name) {
        try {
            Object value = mBeanServer.invoke(oName, name, EMPTY_PARAMS, EMPTY_SIGNATURE);
            if (value != null) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Name " + oName + " has " + name + " value: " + value);
                }
                if (value instanceof Boolean) {
                    Boolean b = (Boolean) value;
                    return b.booleanValue();
                } else {
                    LOGGER.warn("Got value " + value + " of type " + value.getClass() + " for " + name + " on " + oName);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Name " + oName + " could not find attribute " + name + ". " + e, e);
        }
        return false;
    }

    protected void unregisterApiEndpoint(Container container, ObjectName oName) {
        String path = null;
        String address = registeredUrls.get(oName.toString());
        try {
            // TODO there's no way to grok if its a REST or WS API so lets remove both just in case
            path = getPath(container, oName, address, true);
            removeZkPath(path);
            path = getPath(container, oName, address, false);
            removeZkPath(path);
        } catch (Exception e) {
            LOGGER.error("Failed to unregister API endpoint at {}.", path, e);
        } finally{
            registeredUrls.remove(oName.toString());
        }
    }

    protected void removeZkPath(String path) throws Exception {
        CuratorFramework curator = this.curator.get();
        if (curator != null && ZooKeeperUtils.exists(curator, path) != null) {
            LOGGER.info("Unregister API at " + path);
            ZooKeeperUtils.deleteSafe(curator, path);
        }
        registeredZkPaths.remove(path);
    }

    protected String getCxfServletPath(ObjectName oName) throws IOException, URISyntaxException {
        String cxfBus = null;
        // try find it in JMX
        try {
            Object value = mBeanServer.getAttribute(oName, "ServletContext");
            if (value instanceof String) {
                cxfBus = (String) value;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to get CxfServlet attribute on " + oName + ". " + e, e);
        }
        if (Strings.isNullOrBlank(cxfBus)) {
            // lets try find it in OSGi config admin
            try {
                ConfigurationAdmin admin = getConfigAdmin();
                if (admin != null) {
                    Configuration configuration = admin.getConfiguration("org.apache.cxf.osgi");
                    if (configuration != null) {
                        Dictionary<String, Object> properties = configuration.getProperties();
                        if (properties != null) {
                            Object value = properties.get("org.apache.cxf.servlet.context");
                            if (value != null) {
                                cxfBus = value.toString();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to lookup the cxf servlet path. " + e, e);
            }
        }
        if (Strings.isNullOrBlank(cxfBus)) {
            cxfBus = "/cxf";
            LOGGER.warn("Could not find the CXF servlet path in config admin so using a default value: " + cxfBus);
        } else {
            LOGGER.info("Found CXF servlet path from config admin: " + cxfBus);
        }
        return cxfBus;
    }

    protected boolean isFullAddress(String address) {
        return address.startsWith("http:") || address.startsWith("https:") || address.contains("://");
    }

    protected String getPath(Container container, ObjectName oName, String address, boolean restApi) {
        return getPath(container, oName, address, restApi, null);
    }
    protected String getPath(Container container, ObjectName oName, String address, boolean restApi, String specificVersion) {
        String containerId = container.getId();

        String name = oName.getKeyProperty("port");
        if (Strings.isNullOrBlank(name)) {
            name = "Unknown";
        }
        // trim quotes
        if (name.startsWith("\"") && name.endsWith("\"")) {
            name = name.substring(1, name.length() - 1);
        }
        String version = container.getVersionId();
        if(specificVersion != null){
            version = specificVersion;
        }

        String endpointPath = address;
        if (isFullAddress(address)) {
            // lets remove the prefix "http://localhost:8181/cxf/"
            int idx = address.indexOf(":");
            if (idx > 0) {
                int length = address.length();
                // trim leading slashes after colon
                while (++idx < length && address.charAt(idx) == '/') ;
                idx = address.indexOf('/', idx);
                if (idx > 0) {
                    int nextIdx = address.indexOf('/', idx + 1);
                    if (nextIdx > 0) {
                        idx = nextIdx;
                    }
                }
                endpointPath = address.substring(idx);
            }
        }
        String fullName = name;
        if (Strings.isNotBlank(endpointPath)) {
            String prefix = endpointPath.startsWith("/") ? "" : "/";
            fullName += prefix + endpointPath;
        }
        // lets remove any double // or trailing or preceding slashes
        fullName = fullName.replaceAll("//", "/");
        while (fullName.startsWith("/")) {
            fullName = fullName.substring(1);
        }
        while (fullName.endsWith("/")) {
            fullName = fullName.substring(0, fullName.length() - 1);
        }
        if (restApi) {
            return ZkPath.API_REST_ENDPOINTS.getPath(fullName, version, containerId);
        } else {
            return ZkPath.API_WS_ENDPOINTS.getPath(fullName, version, containerId);
        }
    }

    private static ObjectName objectNameFor(String name) {
        try {
            return new ObjectName(name);
        } catch (MalformedObjectNameException e) {
            return null;
        }
    }

    void bindFabricService(FabricService fabricService) {
        this.fabricService.bind(fabricService);
    }

    void unbindFabricService(FabricService fabricService) {
        this.fabricService.unbind(fabricService);
    }

    void bindCurator(CuratorFramework curator) {
        this.curator.bind(curator);
    }

    void unbindCurator(CuratorFramework curator) {
        this.curator.unbind(curator);
    }

    ConfigurationAdmin getConfigAdmin() {
        return configAdmin;
    }


    @Override
    public void nodeChanged(ChildData previousData, ChildData newData) throws Exception {
        ChildData currentData = versionNodeMonitor.getCurrentData();
        byte[] data = currentData.getData();
        LOGGER.info("Container Version has been updated to version {}, republishing of APIs endpoints", new String(data));

        String oldVersion = ((previousData != null) ? new String(previousData.getData()):"");
        // registered paths have this structure:
        // /fabric/registry/clusters/apis/rest/CustomerService/crm/1.0/root
        for(String path : registeredZkPaths){
            if(path.endsWith(getCurrentContainer().getId())){
                String[] split = path.split("/");
                if(split.length >= 2){
                    String version = split[split.length -2];
                    if(oldVersion.equals(version)){
                        removeZkPath(path);
                    }
                }
            }
        }
        replay();
    }
}
