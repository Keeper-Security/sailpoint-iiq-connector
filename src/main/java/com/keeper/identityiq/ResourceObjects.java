package com.keeper.identityiq;

import static com.keeper.identityiq.ObjectTypes.TYPE_ACCOUNT;
import static com.keeper.identityiq.ObjectTypes.TYPE_FOLDER;
import static com.keeper.identityiq.ObjectTypes.TYPE_NODE;
import static com.keeper.identityiq.ObjectTypes.TYPE_RECORD;
import static com.keeper.identityiq.ObjectTypes.TYPE_ROLE;
import static com.keeper.identityiq.ObjectTypes.TYPE_TEAM;
import static com.keeper.identityiq.Values.asStringList;
import static com.keeper.identityiq.Values.firstNonBlank;
import static com.keeper.identityiq.Values.isBlank;
import static com.keeper.identityiq.Values.listOrEmpty;
import static com.keeper.identityiq.Values.nvl;
import static com.keeper.identityiq.Values.str;
import static com.keeper.identityiq.Values.toStringList;
import static com.keeper.identityiq.Values.trimToNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import sailpoint.connector.Connector;
import sailpoint.connector.ConnectorException;
import sailpoint.object.ResourceObject;

/** Keeper -> IdentityIQ objects: accounts and node, team, role, folder and record entitlements. */
final class ResourceObjects {

    private static final Log log = LogFactory.getLog(ResourceObjects.class);

    private static final String NODE_PATH_SEPARATOR = "\\";

    private ResourceObjects() {
    }

    static ResourceObject toAccount(Map<String, Object> user, Map<String, List<String>> folderMap,
            Map<String, List<String>> recordMap) throws ConnectorException {
        String userId = str(user.get("user_id"));
        if (isBlank(userId)) {
            throw new ConnectorException("A Keeper enterprise user has no user_id.");
        }
        String email = nvl(str(user.get("email")));
        String name = nvl(str(user.get("name")));
        String status = nvl(str(user.get("status")));
        // Only Active users can sign in; Invited, Locked and others are disabled.
        boolean disabled = !status.isEmpty() && !"Active".equals(status);

        ResourceObject account = new ResourceObject(userId, firstNonBlank(name, email, userId), TYPE_ACCOUNT);
        account.setUuid(userId);
        account.setAttribute("userId", userId);
        account.setAttribute("email", email);
        account.setAttribute("name", name);
        account.setAttribute("status", status);
        account.setAttribute("jobTitle", nvl(str(user.get("job_title"))));
        account.setAttribute("twoFactorEnabled", Boolean.TRUE.equals(user.get("2fa_enabled")));
        account.setAttribute("aliases", toStringList(user.get("alias")));
        account.setAttribute("node", user.get("node"));
        account.setAttribute("teams", listOrEmpty(asStringList(user.get("teams"))));
        account.setAttribute("roles", listOrEmpty(asStringList(user.get("roles"))));
        account.setAttribute("folders", listOrEmpty(folderMap.get(email.toLowerCase())));
        account.setAttribute("records", listOrEmpty(recordMap.get(email.toLowerCase())));
        account.setAttribute(Connector.ATT_IIQ_DISABLED, disabled);
        account.setAttribute(Connector.ATT_IIQ_LOCKED, disabled);
        return account;
    }

    private static String buildNodePath(Map<String, Object> node) {
        String parent = trimToNull(str(node.get("parent_node")));
        String name = nvl(str(node.get("name")));
        return parent == null ? name : parent + NODE_PATH_SEPARATOR + name;
    }

    static Map<String, String> buildNodePathMap(List<Map<String, Object>> nodes) {
        Map<String, String> map = new HashMap<String, String>();
        for (Map<String, Object> node : nodes) {
            String path = buildNodePath(node);
            String id = str(node.get("node_id"));
            String existing = map.get(path);
            if (existing == null) {
                map.put(path, id);
            } else if (!existing.equals(id)) {
                log.warn("Keeper node name collision for \"" + path + "\": keeping id " + existing
                        + ", ignoring " + id);
            }
        }
        return map;
    }

    static ResourceObject toNodeEntitlement(Map<String, Object> node) {
        String id = str(node.get("node_id"));
        String path = buildNodePath(node);
        ResourceObject ro = new ResourceObject(id, firstNonBlank(path, id), TYPE_NODE);
        ro.setAttribute("id", id);
        ro.setAttribute("name", nvl(str(node.get("name"))));
        ro.setAttribute("path", path);
        ro.setAttribute("parentId", str(node.get("parent_id")));
        ro.setAttribute("parentPath", str(node.get("parent_node")));
        ro.setAttribute("isolated", Boolean.TRUE.equals(node.get("isolated")));
        return ro;
    }

    static ResourceObject toTeamEntitlement(Map<String, Object> team, Map<String, String> nodePathToId,
            List<String> folders) {
        String id = str(team.get("team_uid"));
        String nodePath = str(team.get("node"));
        ResourceObject ro = new ResourceObject(id, firstNonBlank(str(team.get("name")), id), TYPE_TEAM);
        ro.setAttribute("id", id);
        ro.setAttribute("name", nvl(str(team.get("name"))));
        ro.setAttribute("nodeId", nodePath == null ? null : nodePathToId.get(nodePath));
        ro.setAttribute("nodePath", nodePath);
        ro.setAttribute("restricts", nvl(str(team.get("restricts"))));
        ro.setAttribute("folders", folders);
        return ro;
    }

    static ResourceObject toRoleEntitlement(Map<String, Object> role, Map<String, String> nodePathToId) {
        String id = str(role.get("role_id"));
        String nodePath = str(role.get("node"));
        ResourceObject ro = new ResourceObject(id, firstNonBlank(str(role.get("name")), id), TYPE_ROLE);
        ro.setAttribute("id", id);
        ro.setAttribute("name", nvl(str(role.get("name"))));
        ro.setAttribute("nodeId", nodePath == null ? null : nodePathToId.get(nodePath));
        ro.setAttribute("nodePath", nodePath);
        ro.setAttribute("admin", Boolean.TRUE.equals(role.get("admin")));
        ro.setAttribute("defaultRole", Boolean.TRUE.equals(role.get("default_role")));
        ro.setAttribute("visibleBelow", Boolean.TRUE.equals(role.get("visible_below")));
        return ro;
    }

    /** Non-sharable folders give one entitlement; sharable folders one per permission. */
    static List<ResourceObject> toFolderEntitlements(KeeperFolder folder) {
        List<ResourceObject> out = new ArrayList<ResourceObject>();
        String baseName = firstNonBlank(folder.name, folder.path, folder.uid).trim();
        if (VaultTree.FOLDER_NON_SHARABLE.equals(folder.folderType)) {
            out.add(folderResource(folder, folder.uid, baseName, null, null));
            return out;
        }
        for (String permission : VaultTree.permissionsForFolderType(folder.folderType)) {
            String label = VaultTree.permissionLabel(folder.folderType, permission);
            out.add(folderResource(folder, folder.uid + ":" + permission,
                    baseName + " [" + label + "]", permission, label));
        }
        return out;
    }

    private static ResourceObject folderResource(KeeperFolder folder, String id, String name,
            String permission, String label) {
        ResourceObject ro = new ResourceObject(id, name, TYPE_FOLDER);
        ro.setAttribute("id", id);
        ro.setAttribute("uid", folder.uid);
        ro.setAttribute("name", name);
        ro.setAttribute("path", firstNonBlank(folder.path, folder.name, folder.uid));
        ro.setAttribute("folderType", folder.folderType);
        ro.setAttribute("parentId", folder.parentId);
        ro.setAttribute("permission", permission);
        ro.setAttribute("permissionLabel", label);
        return ro;
    }

    static ResourceObject toRecordEntitlement(KeeperRecord record) {
        String displayName = record.title + " [" + record.permission + "]";
        ResourceObject ro = new ResourceObject(record.recordUidPerm, displayName, TYPE_RECORD);
        ro.setAttribute("id", record.recordUidPerm);
        ro.setAttribute("displayName", displayName);
        ro.setAttribute("name", record.title);
        ro.setAttribute("record_uid", record.recordUid);
        ro.setAttribute("permission", record.permission);
        ro.setAttribute("record_category", record.recordCategory);
        ro.setAttribute("type", record.type);
        ro.setAttribute("path", record.path);
        return ro;
    }
}
