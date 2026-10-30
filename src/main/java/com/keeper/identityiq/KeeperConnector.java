package com.keeper.identityiq;

import static com.keeper.identityiq.ObjectTypes.SUPPORTED_TYPES;
import static com.keeper.identityiq.ObjectTypes.TYPE_ACCOUNT;
import static com.keeper.identityiq.Values.isBlank;
import static com.keeper.identityiq.Values.str;
import static com.keeper.identityiq.Values.trimToNull;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import sailpoint.connector.AbstractConnector;
import sailpoint.connector.ConnectorException;
import sailpoint.object.Application;
import sailpoint.object.AttributeDefinition;
import sailpoint.object.Filter;
import sailpoint.object.ProvisioningPlan;
import sailpoint.object.ProvisioningPlan.AccountRequest;
import sailpoint.object.ProvisioningResult;
import sailpoint.object.ResourceObject;
import sailpoint.tools.CloseableIterator;
import sailpoint.tools.Message;

/**
 * IdentityIQ connector for Keeper Security, driven through Keeper Commander
 * Service Mode API v2.
 *
 * Object types: account, node, team, role, folder, record.
 * Folder and record entitlements carry the permission level in their
 * identity ("uid:CODE").
 *
 * This class implements the IdentityIQ Connector interface and reads the
 * application settings; the work is done by:
 * <ul>
 *   <li>{@link ServiceModeClient}: Service Mode API v2 calls and the sync cache</li>
 *   <li>{@link KeeperDirectory}: Commander reads (users, teams, roles, nodes, vault tree)</li>
 *   <li>{@link KeeperAggregator}: account and entitlement aggregation</li>
 *   <li>{@link AccountProvisioner}: create, update, delete, disable and enable</li>
 *   <li>{@link VaultTree} and {@link ResourceObjects}: Keeper data to IdentityIQ objects</li>
 * </ul>
 */
public class KeeperConnector extends AbstractConnector {

    private static final Log log = LogFactory.getLog(KeeperConnector.class);

    // Application configuration attributes
    private static final String ATTR_SERVICE_URL = "serviceUrl";
    private static final String ATTR_API_KEY = "apiKey";
    private static final String ATTR_POLL_TIMEOUT = "pollTimeoutSeconds";
    private static final String ATTR_SYNC_TTL = "syncCacheTtlSeconds";

    private static final int DEFAULT_POLL_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_SYNC_CACHE_TTL_SECONDS = 30;

    private final KeeperDirectory directory;
    private final KeeperAggregator aggregator;
    private final AccountProvisioner provisioner;

    public KeeperConnector(Application application) {
        super(application);
        ServiceModeClient client = new ServiceModeClient(new ServiceModeClient.Settings() {
            @Override
            public String serviceUrl() throws ConnectorException {
                return getServiceUrl();
            }

            @Override
            public String apiKey() throws ConnectorException {
                return getApiKey();
            }

            @Override
            public int pollTimeoutSeconds() {
                return configInt(ATTR_POLL_TIMEOUT, DEFAULT_POLL_TIMEOUT_SECONDS, 1);
            }

            @Override
            public int syncCacheTtlSeconds() {
                return configInt(ATTR_SYNC_TTL, DEFAULT_SYNC_CACHE_TTL_SECONDS, 0);
            }
        });
        directory = new KeeperDirectory(client);
        aggregator = new KeeperAggregator(client, directory);
        provisioner = new AccountProvisioner(client, directory, aggregator);
    }

    // =====================================================================
    // Connector interface
    // =====================================================================

    @Override
    public List<Application.Feature> getSupportedFeatures() {
        return Arrays.asList(
                Application.Feature.PROVISIONING,
                Application.Feature.SYNC_PROVISIONING,
                Application.Feature.ENABLE,
                Application.Feature.UNLOCK);
    }

    /**
     * Connection settings. IdentityIQ 9.0 builds the application's Settings
     * page from these; 8.x shows KeeperAttributesForm.xhtml (formPath) instead.
     */
    @Override
    public List<AttributeDefinition> getDefaultAttributes() {
        return Arrays.asList(
                setting(ATTR_SERVICE_URL, AttributeDefinition.TYPE_STRING, "Service Mode URL",
                        "Keeper Commander Service Mode URL, without /api/v2", true, null),
                setting(ATTR_API_KEY, AttributeDefinition.TYPE_SECRET, "API Key",
                        "Service Mode API key", true, null),
                setting(ATTR_POLL_TIMEOUT, AttributeDefinition.TYPE_INT, "Poll Timeout (seconds)",
                        "How long to wait for a Commander command. Default 60.", false,
                        String.valueOf(DEFAULT_POLL_TIMEOUT_SECONDS)),
                setting(ATTR_SYNC_TTL, AttributeDefinition.TYPE_INT, "Sync Cache TTL (seconds)",
                        "How long an enterprise/vault sync stays fresh. Default 30.", false,
                        String.valueOf(DEFAULT_SYNC_CACHE_TTL_SECONDS)));
    }

    private static AttributeDefinition setting(String name, String type, String displayName,
            String description, boolean required, String defaultValue) {
        AttributeDefinition def = new AttributeDefinition();
        def.setName(name);
        def.setType(type);
        def.setDisplayName(displayName);
        def.setDescription(description);
        def.setRequired(required);
        def.setDefaultValue(defaultValue);
        return def;
    }

    /**
     * Lightweight auth/connectivity check: a fresh whoami. Also warms the
     * whoami cache used by the service account check. Fails if Commander is
     * not logged in.
     */
    @Override
    public void testConfiguration() throws ConnectorException {
        directory.refreshWhoamiUser();
    }

    @Override
    public ResourceObject getObject(String objectType, String identity,
            Map<String, Object> options) throws ConnectorException {
        requireSupportedType(objectType);
        if (isBlank(identity)) {
            throw new ConnectorException("The object identity must not be empty.");
        }
        if (TYPE_ACCOUNT.equals(objectType)) {
            return aggregator.getAccount(identity);
        }
        for (ResourceObject entitlement : aggregator.listEntitlements(objectType)) {
            if (identity.equals(entitlement.getIdentity())) {
                return entitlement;
            }
        }
        return null;
    }

    @Override
    public CloseableIterator<ResourceObject> iterateObjects(String objectType,
            Filter filter, Map<String, Object> options) throws ConnectorException {
        requireSupportedType(objectType);
        List<ResourceObject> objects = TYPE_ACCOUNT.equals(objectType)
                ? aggregator.listAccounts() : aggregator.listEntitlements(objectType);
        log.info("Keeper " + objectType + " aggregation returned " + objects.size() + " objects");
        return new ResourceObjectIterator(objects);
    }

    @Override
    public ProvisioningResult provision(ProvisioningPlan plan) throws ConnectorException {
        ProvisioningResult planResult = new ProvisioningResult();
        planResult.setStatus(ProvisioningResult.STATUS_COMMITTED);

        List<AccountRequest> requests = plan == null ? null : plan.getAccountRequests();
        if (requests == null) {
            return planResult;
        }

        for (AccountRequest request : requests) {
            ProvisioningResult result = new ProvisioningResult();
            result.setStatus(ProvisioningResult.STATUS_COMMITTED);
            try {
                provisioner.provisionAccount(request, result);
            } catch (Exception e) {
                log.error("Keeper provisioning failed for " + request.getNativeIdentity(), e);
                result.setStatus(ProvisioningResult.STATUS_FAILED);
                result.addError(e.getMessage());
            }
            request.setResult(result);
            if (ProvisioningResult.STATUS_FAILED.equals(result.getStatus())) {
                planResult.setStatus(ProvisioningResult.STATUS_FAILED);
                if (result.getErrors() != null) {
                    for (Message error : result.getErrors()) {
                        planResult.addError(error);
                    }
                }
            }
        }
        return planResult;
    }

    // =====================================================================
    // Configuration
    // =====================================================================

    private String getServiceUrl() throws ConnectorException {
        String value = configString(ATTR_SERVICE_URL);
        if (value == null) {
            throw new ConnectorException("Missing required application attribute: " + ATTR_SERVICE_URL);
        }
        return value.replaceAll("/+$", "");
    }

    /** The API key, decrypted when the application stores it encrypted. */
    private String getApiKey() throws ConnectorException {
        if (configString(ATTR_API_KEY) == null) {
            throw new ConnectorException("Missing required application attribute: " + ATTR_API_KEY);
        }
        String value;
        try {
            value = trimToNull(getEncryptedAttr(ATTR_API_KEY));
        } catch (Exception e) {
            // Outside a running IdentityIQ there is no context to decrypt with.
            value = configString(ATTR_API_KEY);
        }
        if (value == null) {
            throw new ConnectorException("Missing required application attribute: " + ATTR_API_KEY);
        }
        return value;
    }

    private String configString(String name) {
        return trimToNull(str(getApplication().getAttributeValue(name)));
    }

    private int configInt(String name, int defaultValue, int minimum) {
        String value = configString(name);
        if (value == null) return defaultValue;
        try {
            int parsed = (int) Double.parseDouble(value);
            return parsed < minimum ? defaultValue : parsed;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void requireSupportedType(String objectType) throws ConnectorException {
        if (!SUPPORTED_TYPES.contains(objectType)) {
            throw new ConnectorException("Unsupported object type \"" + objectType
                    + "\"; expected one of: " + SUPPORTED_TYPES);
        }
    }
}
