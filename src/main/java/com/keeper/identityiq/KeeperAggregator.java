package com.keeper.identityiq;

import static com.keeper.identityiq.ObjectTypes.TYPE_FOLDER;
import static com.keeper.identityiq.ObjectTypes.TYPE_NODE;
import static com.keeper.identityiq.ObjectTypes.TYPE_RECORD;
import static com.keeper.identityiq.ObjectTypes.TYPE_ROLE;
import static com.keeper.identityiq.ObjectTypes.TYPE_TEAM;
import static com.keeper.identityiq.Values.str;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import sailpoint.connector.ConnectorException;
import sailpoint.object.ResourceObject;

/** Account and entitlement aggregation, and single-account reads. */
final class KeeperAggregator {

    private final ServiceModeClient client;
    private final KeeperDirectory directory;

    KeeperAggregator(ServiceModeClient client, KeeperDirectory directory) {
        this.client = client;
        this.directory = directory;
    }

    List<ResourceObject> listAccounts() throws ConnectorException {
        client.syncEnterprise();
        client.syncVault();
        // One vault-tree round-trip for both folders and records.
        Map<String, Object> vaultTree = directory.listVaultTree();
        Map<String, List<String>> folderMap =
                VaultTree.buildUserFolderMap(VaultTree.getAllShareableFolders(vaultTree));
        Map<String, List<String>> recordMap = VaultTree.buildUserRecordMap(VaultTree.getRecordList(vaultTree));

        List<ResourceObject> accounts = new ArrayList<ResourceObject>();
        for (Map<String, Object> user : directory.listUsers()) {
            accounts.add(ResourceObjects.toAccount(user, folderMap, recordMap));
        }
        return accounts;
    }

    List<ResourceObject> listEntitlements(String type) throws ConnectorException {
        client.syncVault();
        client.syncEnterprise();
        List<ResourceObject> out = new ArrayList<ResourceObject>();

        if (TYPE_NODE.equals(type)) {
            for (Map<String, Object> node : directory.listNodes()) {
                out.add(ResourceObjects.toNodeEntitlement(node));
            }
        } else if (TYPE_TEAM.equals(type)) {
            Map<String, String> nodePathToId = ResourceObjects.buildNodePathMap(directory.listNodes());
            Map<String, List<String>> teamFolders =
                    VaultTree.buildTeamFolderMap(VaultTree.getAllShareableFolders(directory.listVaultTree()));
            for (Map<String, Object> team : directory.listTeams()) {
                List<String> folders = teamFolders.get(str(team.get("team_uid")));
                out.add(ResourceObjects.toTeamEntitlement(team, nodePathToId,
                        folders == null ? new ArrayList<String>() : folders));
            }
        } else if (TYPE_ROLE.equals(type)) {
            Map<String, String> nodePathToId = ResourceObjects.buildNodePathMap(directory.listNodes());
            for (Map<String, Object> role : directory.listRoles()) {
                out.add(ResourceObjects.toRoleEntitlement(role, nodePathToId));
            }
        } else if (TYPE_FOLDER.equals(type)) {
            for (KeeperFolder folder : VaultTree.getAllShareableFolders(directory.listVaultTree())) {
                out.addAll(ResourceObjects.toFolderEntitlements(folder));
            }
        } else if (TYPE_RECORD.equals(type)) {
            for (KeeperRecord record : VaultTree.getRecordList(directory.listVaultTree())) {
                out.add(ResourceObjects.toRecordEntitlement(record));
            }
        }
        return out;
    }

    /** The account for a native identity (user_id) or email, or null if Keeper has no such user. */
    ResourceObject getAccount(String identity) throws ConnectorException {
        client.syncEnterprise();
        client.syncVault();
        Map<String, Object> user = directory.findUser(identity);
        return user == null ? null : loadAccountView(user, null);
    }

    /** Builds the account view for one user (folders and records from one vault tree). */
    ResourceObject loadAccountView(Map<String, Object> user, Map<String, Object> vaultTree)
            throws ConnectorException {
        Map<String, Object> tree = vaultTree != null ? vaultTree : directory.listVaultTree();
        return ResourceObjects.toAccount(user,
                VaultTree.buildUserFolderMap(VaultTree.getAllShareableFolders(tree)),
                VaultTree.buildUserRecordMap(VaultTree.getRecordList(tree)));
    }
}
