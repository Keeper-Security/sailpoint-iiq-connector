package com.keeper.identityiq;

import static com.keeper.identityiq.Values.escapeArg;
import static com.keeper.identityiq.Values.isBlank;
import static com.keeper.identityiq.Values.nvl;
import static com.keeper.identityiq.Values.stableId;
import static com.keeper.identityiq.Values.stableIdList;
import static com.keeper.identityiq.Values.str;
import static com.keeper.identityiq.Values.trimToNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import sailpoint.connector.ConnectorException;

/** Reads from Keeper through Commander: users, teams, roles, nodes, the vault tree and whoami. */
final class KeeperDirectory {

    // Commander enterprise-info columns
    private static final String USER_COLUMNS = "name,status,transfer_status,node,team_count,teams,"
            + "role_count,roles,alias,2fa_enabled,job_title";
    private static final String TEAM_COLUMNS = "restricts,node,user_count,users,queued_user_count,"
            + "queued_users,role_count,roles";
    private static final String ROLE_COLUMNS = "visible_below,default_role,admin,node,user_count,users,"
            + "team_count,teams";
    private static final String NODE_COLUMNS = "parent_node,parent_id,user_count,users,team_count,teams,"
            + "role_count,roles,provisioning,isolated";

    private final ServiceModeClient client;

    /** Cached whoami user (the Commander service account) for this connector instance. */
    private String whoamiUser;

    KeeperDirectory(ServiceModeClient client) {
        this.client = client;
    }

    List<Map<String, Object>> listUsers() throws ConnectorException {
        List<Map<String, Object>> users = parseArrayData(client.runCommand(
                "enterprise-info --users --format json -v --columns " + USER_COLUMNS).get("data"), "users");
        for (Map<String, Object> user : users) {
            normalizeUserRecord(user);
        }
        return users;
    }

    Map<String, Object> getUserByEmail(String email) throws ConnectorException {
        String trimmed = email.trim();
        List<Map<String, Object>> users = parseArrayData(client.runCommand("enterprise-info \"" + escapeArg(trimmed)
                + "\" --users --format json -v --columns " + USER_COLUMNS).get("data"), "users");
        // Commander pattern is a substring match, so filter to an exact email hit.
        for (Map<String, Object> user : users) {
            if (trimmed.equalsIgnoreCase(str(user.get("email")))) {
                normalizeUserRecord(user);
                return user;
            }
        }
        return null;
    }

    /** Finds a user by native identity (Keeper user_id) or by email. */
    Map<String, Object> findUser(String identity) throws ConnectorException {
        if (isBlank(identity)) return null;
        String id = identity.trim();
        if (id.contains("@")) {
            return getUserByEmail(id);
        }
        for (Map<String, Object> user : listUsers()) {
            if (id.equals(str(user.get("user_id")))) return user;
        }
        return null;
    }

    Map<String, Object> requireUser(String identity) throws ConnectorException {
        client.syncEnterprise();
        Map<String, Object> user = findUser(identity);
        if (user == null) {
            throw new ConnectorException("Keeper user \"" + identity + "\" not found");
        }
        return user;
    }

    List<Map<String, Object>> listTeams() throws ConnectorException {
        return parseArrayData(client.runCommand("enterprise-info --teams --format json -v --columns "
                + TEAM_COLUMNS).get("data"), "teams");
    }

    List<Map<String, Object>> listRoles() throws ConnectorException {
        return parseArrayData(client.runCommand("enterprise-info --roles --format json -v --columns "
                + ROLE_COLUMNS).get("data"), "roles");
    }

    List<Map<String, Object>> listNodes() throws ConnectorException {
        return parseArrayData(client.runCommand("enterprise-info --nodes --format json -v --columns "
                + NODE_COLUMNS).get("data"), "nodes");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> listVaultTree() throws ConnectorException {
        Object data = client.runCommand("tree -s -ns -r -v --format json").get("data");
        if (data instanceof String && !((String) data).trim().isEmpty()) {
            data = JsonParser.parse(((String) data).trim());
        }
        if (!(data instanceof Map)) {
            throw new ConnectorException("Unexpected Keeper vault tree response.");
        }
        return (Map<String, Object>) data;
    }

    /**
     * Newer Commander returns node/teams/roles on a user as objects such as
     * {node_id, node_name}; older versions returned scalars. Normalize both
     * to stable ID strings.
     */
    private void normalizeUserRecord(Map<String, Object> user) {
        if (user.containsKey("node")) user.put("node", stableId(user.get("node"), "node_id"));
        if (user.containsKey("teams")) user.put("teams", stableIdList(user.get("teams"), "team_uid"));
        if (user.containsKey("roles")) user.put("roles", stableIdList(user.get("roles"), "role_id"));
    }

    /** Fetches whoami again and caches it (used by test connection). */
    void refreshWhoamiUser() throws ConnectorException {
        whoamiUser = fetchWhoamiUser();
    }

    /** The cached whoami user, fetched on first use. */
    String getWhoamiUser() throws ConnectorException {
        if (whoamiUser == null) {
            whoamiUser = fetchWhoamiUser();
        }
        return trimToNull(whoamiUser);
    }

    /** whoami is a session lookup, so no enterprise or vault sync is needed. */
    private String fetchWhoamiUser() throws ConnectorException {
        Object data = client.runCommand("whoami").get("data");
        return data instanceof Map ? nvl(str(((Map<?, ?>) data).get("user"))) : "";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseArrayData(Object data, String kind) throws ConnectorException {
        if (data instanceof String) {
            String trimmed = ((String) data).trim();
            data = trimmed.isEmpty() ? null : JsonParser.parse(trimmed);
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (data == null) {
            return out;
        }
        if (!(data instanceof List)) {
            throw new ConnectorException("Unexpected Keeper " + kind + " response type.");
        }
        for (Object item : (List<?>) data) {
            if (item instanceof Map) out.add((Map<String, Object>) item);
        }
        return out;
    }
}
