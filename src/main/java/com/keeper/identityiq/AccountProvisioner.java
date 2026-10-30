package com.keeper.identityiq;

import static com.keeper.identityiq.Values.escapeArg;
import static com.keeper.identityiq.Values.escapeArgSafe;
import static com.keeper.identityiq.Values.firstValue;
import static com.keeper.identityiq.Values.listOrEmpty;
import static com.keeper.identityiq.Values.stableIdList;
import static com.keeper.identityiq.Values.str;
import static com.keeper.identityiq.Values.toStringList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import sailpoint.connector.ConnectorException;
import sailpoint.object.ProvisioningPlan;
import sailpoint.object.ProvisioningPlan.AccountRequest;
import sailpoint.object.ProvisioningPlan.AttributeRequest;
import sailpoint.object.ProvisioningResult;
import sailpoint.object.ResourceObject;

/** Applies one account request: create, update, delete, disable and enable. */
final class AccountProvisioner {

    private static final Log log = LogFactory.getLog(AccountProvisioner.class);

    private final ServiceModeClient client;
    private final KeeperDirectory directory;
    private final KeeperAggregator aggregator;

    AccountProvisioner(ServiceModeClient client, KeeperDirectory directory, KeeperAggregator aggregator) {
        this.client = client;
        this.directory = directory;
        this.aggregator = aggregator;
    }

    void provisionAccount(AccountRequest request, ProvisioningResult result)
            throws ConnectorException {
        AccountRequest.Operation op = request.getOperation();
        if (op == null) {
            op = AccountRequest.Operation.Modify;
        }
        switch (op) {
            case Create:
                createAccount(request, result);
                break;
            case Modify:
                updateAccount(request, result);
                break;
            case Delete:
                deleteAccount(request);
                break;
            case Disable:
            case Lock:
                lockAccount(request, result, true);
                break;
            case Enable:
            case Unlock:
                lockAccount(request, result, false);
                break;
            default:
                throw new ConnectorException("Unsupported account operation: " + op);
        }
    }

    private void createAccount(AccountRequest request, ProvisioningResult result)
            throws ConnectorException {
        String email = firstValue(attributeValue(request, "email"));
        if (email == null && request.getNativeIdentity() != null
                && request.getNativeIdentity().contains("@")) {
            email = request.getNativeIdentity().trim();
        }
        if (email == null) {
            throw new ConnectorException("Create requires an email in the \"email\" attribute.");
        }
        String name = firstValue(attributeValue(request, "name"));
        if (name == null) {
            throw new ConnectorException("Create for \"" + email
                    + "\" is missing required attribute \"name\" (the user's display name).");
        }
        String nodeId = requireSingleNodeId(attributeValue(request, "node"),
                "Create for \"" + email + "\" is missing required attribute \"node\" "
                + "(the node_id of the Keeper enterprise node to place the user in).");
        String jobTitle = firstValue(attributeValue(request, "jobTitle"));
        List<String> roles = toStringList(attributeValue(request, "roles"));
        List<String> teams = toStringList(attributeValue(request, "teams"));

        StringBuilder cmd = new StringBuilder("enterprise-user \"" + escapeArg(email) + "\" --add");
        cmd.append(" --name \"").append(escapeArg(name)).append('"');
        if (jobTitle != null) cmd.append(" --job-title \"").append(escapeArg(jobTitle)).append('"');
        cmd.append(" --node \"").append(escapeArg(nodeId)).append('"');
        for (String role : roles) cmd.append(" --add-role \"").append(escapeArg(role)).append('"');
        for (String team : teams) cmd.append(" --add-team \"").append(escapeArg(team)).append('"');

        log.info("Creating Keeper account " + email + " in node " + nodeId
                + ", roles=" + roles + ", teams=" + teams);
        client.runCommand(cmd.toString());
        client.invalidateEnterpriseSync();

        // Read back so IIQ stores Keeper's post-invite state (status "Invited").
        client.syncEnterprise();
        Map<String, Object> user = directory.getUserByEmail(email);
        if (user == null) {
            result.addWarning("Keeper user \"" + email + "\" was created but could not be read back. "
                    + "Commander may still be propagating the invite; the next aggregation will pick it up.");
            return;
        }
        ResourceObject account = aggregator.loadAccountView(user, null);
        request.setNativeIdentity(account.getIdentity());
        result.setObject(account);
    }

    private void updateAccount(AccountRequest request, ProvisioningResult result)
            throws ConnectorException {
        Map<String, Object> user = directory.requireUser(request.getNativeIdentity());
        String email = str(user.get("email"));
        List<AttributeRequest> changes = request.getAttributeRequests();
        if (changes == null || changes.isEmpty()) {
            result.setObject(aggregator.loadAccountView(user, null));
            return;
        }

        assertAtMostOneNodeAssign(changes);
        client.syncEnterprise();
        client.syncVault();

        Map<String, Object> vaultTree = directory.listVaultTree();
        UpdatePlan plan = buildUpdatePlan(email, changes, user, vaultTree);
        if (!plan.hasWork()) {
            log.info("Update for " + email + " produced no actionable changes");
            result.setObject(aggregator.loadAccountView(user, vaultTree));
            return;
        }

        // Role/node/team mutations against the service account can strip the
        // privileges it needs to run; refuse like delete.
        assertNotServiceAccount(email, "update");

        List<String> failures = applyUpdatePlan(email, plan);

        // After a successful --add-alias rename the new address is primary.
        boolean renameFailed = false;
        for (String failure : failures) {
            if (failure.startsWith("email:")) renameFailed = true;
        }
        String resultEmail = plan.newEmail != null && !renameFailed ? plan.newEmail : email;
        client.syncEnterprise();
        client.syncVault();
        Map<String, Object> updated = directory.getUserByEmail(resultEmail);
        if (updated != null) {
            result.setObject(aggregator.loadAccountView(updated, null));
        }

        if (!failures.isEmpty()) {
            result.setStatus(ProvisioningResult.STATUS_FAILED);
            for (String failure : failures) {
                result.addError(failure);
            }
        }
    }

    /**
     * Transfers the user's vault to the transfer target configured on
     * Service Mode, which also removes the leaving account. Idempotent.
     */
    private void deleteAccount(AccountRequest request) throws ConnectorException {
        client.syncEnterprise();
        Map<String, Object> user = directory.findUser(request.getNativeIdentity());
        if (user == null) {
            log.info("Keeper user " + request.getNativeIdentity()
                    + " not found; treating delete as success");
            return;
        }
        String email = str(user.get("email"));
        assertNotServiceAccount(email, "delete");
        client.runCommand("transfer-user \"" + escapeArg(email) + "\" -f");
        client.invalidateEnterpriseSync();
        client.invalidateVaultSync();
        log.info("Transferred and removed Keeper account " + email);
    }

    private void lockAccount(AccountRequest request, ProvisioningResult result, boolean lock)
            throws ConnectorException {
        Map<String, Object> user = directory.requireUser(request.getNativeIdentity());
        String email = str(user.get("email"));
        if (lock) {
            // Locking the service account would break the Service Mode credential.
            assertNotServiceAccount(email, "disable");
        }
        client.runCommand("enterprise-user \"" + escapeArg(email) + "\" " + (lock ? "--lock" : "--unlock"));
        client.invalidateEnterpriseSync();
        client.syncEnterprise();
        Map<String, Object> updated = directory.getUserByEmail(email);
        if (updated != null) {
            result.setObject(aggregator.loadAccountView(updated, null));
        }
    }

    /** Membership deltas and profile changes for one account update. */
    private static final class UpdatePlan {
        String name;
        String jobTitle;
        String nodeId;
        String newEmail;
        final Set<String> addRoles = new LinkedHashSet<String>();
        final Set<String> removeRoles = new LinkedHashSet<String>();
        final Set<String> addTeams = new LinkedHashSet<String>();
        final Set<String> removeTeams = new LinkedHashSet<String>();
        final Set<String> addFolders = new LinkedHashSet<String>();
        final Set<String> removeFolders = new LinkedHashSet<String>();
        final Set<String> addRecords = new LinkedHashSet<String>();
        final Set<String> removeRecords = new LinkedHashSet<String>();

        boolean hasUserMutation() {
            return name != null || jobTitle != null || nodeId != null
                    || !addRoles.isEmpty() || !removeRoles.isEmpty()
                    || !addTeams.isEmpty() || !removeTeams.isEmpty();
        }

        boolean hasWork() {
            return hasUserMutation() || newEmail != null
                    || !addFolders.isEmpty() || !removeFolders.isEmpty()
                    || !addRecords.isEmpty() || !removeRecords.isEmpty();
        }
    }

    private UpdatePlan buildUpdatePlan(String email, List<AttributeRequest> changes,
            Map<String, Object> user, Map<String, Object> vaultTree) throws ConnectorException {
        UpdatePlan plan = new UpdatePlan();
        List<String> currentFolders = null;
        List<String> currentRecords = null;

        for (AttributeRequest change : changes) {
            String attribute = change.getName();
            ProvisioningPlan.Operation op = change.getOperation();
            if (op == null) op = ProvisioningPlan.Operation.Set;
            if (op == ProvisioningPlan.Operation.Retain) continue;
            boolean remove = op == ProvisioningPlan.Operation.Remove
                    || op == ProvisioningPlan.Operation.Revoke;
            Object value = change.getValue();

            if ("name".equals(attribute)) {
                if (remove) {
                    log.info("Ignoring Remove on \"name\": value cannot be cleared");
                    continue;
                }
                String v = firstValue(value);
                if (v == null) throw new ConnectorException("Attribute \"name\" cannot be empty");
                plan.name = v;
            } else if ("jobTitle".equals(attribute)) {
                String v = firstValue(value);
                plan.jobTitle = remove || v == null ? "" : v;
            } else if ("node".equals(attribute)) {
                if (remove) {
                    log.info("Ignoring Remove on \"node\": Keeper users must remain in a node; "
                            + "use Set with a new node id to move the user");
                    continue;
                }
                plan.nodeId = requireSingleNodeId(value, "Attribute \"node\" cannot be empty");
            } else if ("roles".equals(attribute)) {
                applyMultiValuedChange(op, value, plan.addRoles, plan.removeRoles,
                        stableIdList(user.get("roles"), "role_id"));
            } else if ("teams".equals(attribute)) {
                applyMultiValuedChange(op, value, plan.addTeams, plan.removeTeams,
                        stableIdList(user.get("teams"), "team_uid"));
            } else if ("folders".equals(attribute)) {
                if (currentFolders == null) {
                    currentFolders = listOrEmpty(VaultTree.buildUserFolderMap(
                            VaultTree.getAllShareableFolders(vaultTree)).get(email.toLowerCase()));
                }
                applyMultiValuedChange(op, value, plan.addFolders, plan.removeFolders, currentFolders);
            } else if ("records".equals(attribute)) {
                if (currentRecords == null) {
                    currentRecords = VaultTree.getRecordListByEmail(email, vaultTree);
                }
                applyMultiValuedChange(op, value, plan.addRecords, plan.removeRecords, currentRecords);
            } else if ("email".equals(attribute)) {
                if (remove) {
                    log.info("Ignoring Remove on \"email\": primary email cannot be cleared");
                    continue;
                }
                String desired = firstValue(value);
                if (desired == null) throw new ConnectorException("Attribute \"email\" cannot be empty");
                if (!desired.equalsIgnoreCase(email)) plan.newEmail = desired;
            } else if ("aliases".equals(attribute)) {
                throw new ConnectorException("Cannot manage \"aliases\": Keeper --add-alias promotes the "
                        + "address to primary. To rename the account, Set the \"email\" attribute.");
            } else {
                log.warn("Ignoring read-only or unknown attribute \"" + attribute + "\" for " + email);
            }
        }
        return plan;
    }

    /** Diffs one multi-valued change into adds/removes against the current values. */
    private void applyMultiValuedChange(ProvisioningPlan.Operation op, Object value,
            Set<String> adds, Set<String> removes, List<String> current) {
        List<String> values = toStringList(value);
        if (op == ProvisioningPlan.Operation.Add) {
            adds.addAll(values);
        } else if (op == ProvisioningPlan.Operation.Remove || op == ProvisioningPlan.Operation.Revoke) {
            removes.addAll(values);
        } else {
            for (String id : values) {
                if (!current.contains(id)) adds.add(id);
            }
            for (String id : current) {
                if (!values.contains(id)) removes.add(id);
            }
        }
    }

    private void assertAtMostOneNodeAssign(List<AttributeRequest> changes) throws ConnectorException {
        int count = 0;
        for (AttributeRequest change : changes) {
            if ("node".equals(change.getName())
                    && change.getOperation() != ProvisioningPlan.Operation.Remove) {
                count++;
            }
        }
        if (count > 1) {
            throw new ConnectorException("node is single-valued; expected at most one \"node\" change, got "
                    + count);
        }
    }

    /**
     * Applies the plan: profile/node/roles/teams first, then folders, then
     * records, then the email rename. Each folder/record change is
     * independent; failures are collected as "attribute: message".
     */
    private List<String> applyUpdatePlan(String email, UpdatePlan plan) {
        List<String> failures = new ArrayList<String>();

        if (plan.hasUserMutation()) {
            StringBuilder cmd = new StringBuilder("enterprise-user \"" + escapeArgSafe(email) + "\" -f");
            try {
                if (plan.name != null) cmd.append(" --name \"").append(escapeArg(plan.name)).append('"');
                if (plan.jobTitle != null) cmd.append(" --job-title \"").append(escapeArg(plan.jobTitle)).append('"');
                if (plan.nodeId != null) cmd.append(" --node \"").append(escapeArg(plan.nodeId)).append('"');
                // Removes before adds so Commander does not reject duplicates.
                for (String v : plan.removeRoles) cmd.append(" --remove-role \"").append(escapeArg(v)).append('"');
                for (String v : plan.removeTeams) cmd.append(" --remove-team \"").append(escapeArg(v)).append('"');
                for (String v : plan.addRoles) cmd.append(" --add-role \"").append(escapeArg(v)).append('"');
                for (String v : plan.addTeams) cmd.append(" --add-team \"").append(escapeArg(v)).append('"');
                client.runCommand(cmd.toString());
                client.invalidateEnterpriseSync();
            } catch (ConnectorException e) {
                failures.add(primaryUserMutationAttribute(plan) + ": update node/roles/teams for "
                        + email + " failed: " + e.getMessage());
            }
        }

        if (!plan.addFolders.isEmpty() || !plan.removeFolders.isEmpty()) {
            failures.addAll(applyFolderChanges(email, plan.addFolders, plan.removeFolders));
        }

        for (String id : plan.removeRecords) {
            try {
                client.runCommand(recordShareCommand(id, email) + " --action revoke");
                client.invalidateVaultSync();
            } catch (ConnectorException e) {
                failures.add("records: revoke record share \"" + id + "\" failed: " + e.getMessage());
            }
        }
        for (String id : plan.addRecords) {
            try {
                client.runCommand(recordShareCommand(id, email) + " --action grant");
                client.invalidateVaultSync();
            } catch (ConnectorException e) {
                failures.add("records: grant record share \"" + id + "\" failed: " + e.getMessage());
            }
        }

        // Keeper --add-alias <new> promotes <new> to primary: the supported rename path.
        if (plan.newEmail != null) {
            try {
                client.runCommand("enterprise-user \"" + escapeArg(email) + "\" -f --add-alias \""
                        + escapeArg(plan.newEmail) + "\"");
                client.invalidateEnterpriseSync();
            } catch (ConnectorException e) {
                failures.add("email: rename " + email + " to " + plan.newEmail + " failed: " + e.getMessage());
            }
        }
        return failures;
    }

    private String primaryUserMutationAttribute(UpdatePlan plan) {
        if (plan.name != null) return "name";
        if (plan.jobTitle != null) return "jobTitle";
        if (plan.nodeId != null) return "node";
        if (!plan.addRoles.isEmpty() || !plan.removeRoles.isEmpty()) return "roles";
        return "teams";
    }

    /**
     * Grants/removes classic and NSF folder shares. When the same folder is
     * both removed and granted (permission change), only the grant runs.
     */
    private List<String> applyFolderChanges(String email, Set<String> adds, Set<String> removes) {
        List<String> failures = new ArrayList<String>();
        Map<String, KeeperFolder> byUid = new HashMap<String, KeeperFolder>();
        try {
            for (KeeperFolder folder : VaultTree.getAllShareableFolders(directory.listVaultTree())) {
                byUid.put(folder.uid, folder);
            }
        } catch (ConnectorException e) {
            failures.add("folders: could not load the vault tree: " + e.getMessage());
            return failures;
        }

        Set<String> addUids = new LinkedHashSet<String>();
        for (String id : adds) {
            addUids.add(VaultTree.parseFolderEntitlementId(id)[0]);
        }

        for (String id : removes) {
            String uid = VaultTree.parseFolderEntitlementId(id)[0];
            if (addUids.contains(uid)) continue;
            try {
                KeeperFolder folder = requireSharableFolder(byUid, uid, "remove");
                if (VaultTree.FOLDER_CLASSIC.equals(folder.folderType)) {
                    client.runCommand("share-folder -a remove -e \"" + escapeArg(email) + "\" -f \""
                            + escapeArg(uid) + "\"");
                } else {
                    client.runCommand("nsf-share-folder -a remove -e \"" + escapeArg(email) + "\" \""
                            + escapeArg(uid) + "\"");
                }
                client.invalidateVaultSync();
            } catch (ConnectorException e) {
                failures.add("folders: remove folder share \"" + id + "\" failed: " + e.getMessage());
            }
        }

        for (String id : adds) {
            try {
                String[] parsed = VaultTree.parseFolderEntitlementId(id);
                String uid = parsed[0];
                String permission = parsed[1];
                KeeperFolder folder = requireSharableFolder(byUid, uid, "grant");
                if (permission == null
                        || !VaultTree.permissionsForFolderType(folder.folderType).contains(permission)) {
                    throw new ConnectorException("invalid folder entitlement \"" + id
                            + "\" for folderType \"" + folder.folderType + "\"");
                }
                if (VaultTree.FOLDER_CLASSIC.equals(folder.folderType)) {
                    boolean manageUsers = permission.equals("MU") || permission.equals("MUR");
                    boolean manageRecords = permission.equals("MR") || permission.equals("MUR");
                    client.runCommand("share-folder -a grant -e \"" + escapeArg(email) + "\" -o "
                            + (manageUsers ? "on" : "off") + " -p " + (manageRecords ? "on" : "off")
                            + " \"" + escapeArg(uid) + "\"");
                } else {
                    client.runCommand("nsf-share-folder -a grant -e \"" + escapeArg(email) + "\" -r "
                            + VaultTree.nsfRole(permission) + " \"" + escapeArg(uid) + "\"");
                }
                client.invalidateVaultSync();
            } catch (ConnectorException e) {
                failures.add("folders: grant folder share \"" + id + "\" failed: " + e.getMessage());
            }
        }
        return failures;
    }

    private KeeperFolder requireSharableFolder(Map<String, KeeperFolder> byUid, String uid, String action)
            throws ConnectorException {
        KeeperFolder folder = byUid.get(uid);
        if (folder == null) {
            throw new ConnectorException("Keeper folder with uid \"" + uid + "\" not found (" + action + ")");
        }
        if (VaultTree.FOLDER_NON_SHARABLE.equals(folder.folderType)) {
            throw new ConnectorException("cannot " + action + " share on non-sharable folder \"" + uid + "\"");
        }
        return folder;
    }

    /** Builds the share-record / nsf-share-record command for a "uid:CODE" entitlement. */
    private String recordShareCommand(String recordId, String email) throws ConnectorException {
        String[] parts = recordId.split(":", 2);
        String uid = escapeArg(parts[0].trim());
        String perm = parts.length > 1 ? parts[1] : "";
        String e = escapeArg(email);
        if ("RO".equals(perm)) return "share-record -e \"" + e + "\" \"" + uid + "\"";
        if ("CE".equals(perm)) return "share-record -e \"" + e + "\" \"" + uid + "\" --write";
        if ("CS".equals(perm)) return "share-record -e \"" + e + "\" \"" + uid + "\" --share";
        if ("VW".equals(perm)) return "nsf-share-record -e \"" + e + "\" \"" + uid + "\" -r viewer";
        if ("SM".equals(perm)) return "nsf-share-record -e \"" + e + "\" \"" + uid + "\" -r share-manager";
        if ("FM".equals(perm)) return "nsf-share-record -e \"" + e + "\" \"" + uid + "\" -r full-manager";
        if ("CSM".equals(perm)) return "nsf-share-record -e \"" + e + "\" \"" + uid + "\" -r content-share-manager";
        if ("CM".equals(perm)) return "nsf-share-record -e \"" + e + "\" \"" + uid + "\" -r content-manager";
        if ("OW".equals(perm)) {
            throw new ConnectorException("Ownership change is restricted. Please perform this action in the Keeper Vault.");
        }
        throw new ConnectorException("Invalid record permission: " + perm);
    }

    /**
     * Refuses a mutation against the Commander service account (the whoami
     * user). Deleting, disabling or updating it strands the application
     * with no working provisioning credentials.
     */
    private void assertNotServiceAccount(String email, String action) throws ConnectorException {
        String serviceAccount = directory.getWhoamiUser();
        if (serviceAccount != null && serviceAccount.equalsIgnoreCase(email)) {
            throw new ConnectorException("Refusing to " + action + " \"" + email + "\": this is the Commander "
                    + "service account backing the IdentityIQ application. Changing it would break provisioning.");
        }
    }

    private Object attributeValue(AccountRequest request, String name) {
        AttributeRequest attribute = request.getAttributeRequest(name);
        return attribute == null ? null : attribute.getValue();
    }

    /** Keeper users belong to exactly one node: accept one id, reject none or many. */
    private String requireSingleNodeId(Object value, String emptyMessage) throws ConnectorException {
        List<String> values = toStringList(value);
        if (values.isEmpty()) throw new ConnectorException(emptyMessage);
        if (values.size() > 1) {
            throw new ConnectorException("node is single-valued; expected one node id, got " + values.size());
        }
        return values.get(0);
    }
}
