package com.keeper.identityiq;

import static com.keeper.identityiq.Values.addToMap;
import static com.keeper.identityiq.Values.childrenOf;
import static com.keeper.identityiq.Values.firstNonBlank;
import static com.keeper.identityiq.Values.listOrEmpty;
import static com.keeper.identityiq.Values.nvl;
import static com.keeper.identityiq.Values.shareEntries;
import static com.keeper.identityiq.Values.str;
import static com.keeper.identityiq.Values.toStringList;
import static com.keeper.identityiq.Values.trimToNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Folders, records and permission codes from Commander's vault tree
 * ("tree -s -ns -r -v --format json").
 */
final class VaultTree {

    // Folder types and permission codes
    static final String FOLDER_CLASSIC = "classic";
    static final String FOLDER_NSF = "nsf";
    static final String FOLDER_NON_SHARABLE = "non-sharable";
    private static final List<String> CLASSIC_PERMISSIONS = Arrays.asList("NP", "MU", "MR", "MUR");
    private static final List<String> NSF_PERMISSIONS = Arrays.asList("VW", "SM", "CM", "CSM", "FM");
    private static final Map<String, String> CLASSIC_PERMISSION_LABELS = new HashMap<String, String>();
    private static final Map<String, String> NSF_PERMISSION_LABELS = new HashMap<String, String>();
    private static final Map<String, String> NSF_ROLES = new HashMap<String, String>();
    private static final Set<String> CONTAINER_KINDS = new LinkedHashSet<String>(
            Arrays.asList("shared_folder", "folder", "nested_share_folder"));

    static {
        CLASSIC_PERMISSION_LABELS.put("NP", "No permissions");
        CLASSIC_PERMISSION_LABELS.put("MU", "Manage users");
        CLASSIC_PERMISSION_LABELS.put("MR", "Manage records");
        CLASSIC_PERMISSION_LABELS.put("MUR", "Manage users & records");
        NSF_PERMISSION_LABELS.put("VW", "Viewer");
        NSF_PERMISSION_LABELS.put("SM", "Share manager");
        NSF_PERMISSION_LABELS.put("CM", "Content manager");
        NSF_PERMISSION_LABELS.put("CSM", "Content share manager");
        NSF_PERMISSION_LABELS.put("FM", "Full manager");
        NSF_ROLES.put("VW", "viewer");
        NSF_ROLES.put("SM", "share-manager");
        NSF_ROLES.put("CM", "content-manager");
        NSF_ROLES.put("CSM", "content-share-manager");
        NSF_ROLES.put("FM", "full-manager");
    }

    private VaultTree() {
    }

    static List<KeeperFolder> getAllShareableFolders(Map<String, Object> vaultTree) {
        List<KeeperFolder> out = new ArrayList<KeeperFolder>();
        collectFolders(childrenOf(vaultTree.get("tree")), null, out, new LinkedHashSet<String>());
        return out;
    }

    private static void collectFolders(List<Map<String, Object>> nodes, String parentId,
            List<KeeperFolder> out, Set<String> seen) {
        for (Map<String, Object> node : nodes) {
            String uid = trimToNull(str(node.get("uid")));
            String kind = str(node.get("kind"));
            String folderType = folderTypeForKind(kind);
            if (folderType != null && uid != null && !seen.contains(uid)) {
                seen.add(uid);
                out.add(toKeeperFolder(node, uid, parentId, folderType));
            }
            // Always walk children so nested shared folders are found.
            List<Map<String, Object>> children = childrenOf(node);
            if (CONTAINER_KINDS.contains(kind) && !children.isEmpty()) {
                collectFolders(children, uid != null ? uid : parentId, out, seen);
            }
        }
    }

    private static KeeperFolder toKeeperFolder(Map<String, Object> node, String uid, String parentId,
            String folderType) {
        KeeperFolder folder = new KeeperFolder();
        folder.uid = uid;
        folder.name = nvl(str(node.get("name")));
        String rawPath = firstNonBlank(str(node.get("path")), str(node.get("name")), uid).trim();
        folder.path = firstNonBlank(rawPath.replaceAll("^/+", ""), folder.name, uid);
        folder.folderType = folderType;
        folder.parentId = parentId;
        for (Map<String, Object> user : shareEntries(node, "users")) {
            String email = trimToNull(str(user.get("email")));
            if (email != null) folder.userPermissions.put(email.toLowerCase(), toStringList(user.get("permissions")));
        }
        for (Map<String, Object> team : shareEntries(node, "teams")) {
            String teamUid = trimToNull(str(team.get("uid")));
            if (teamUid != null) folder.teamPermissions.put(teamUid, toStringList(team.get("permissions")));
        }
        return folder;
    }

    private static String folderTypeForKind(String kind) {
        if ("shared_folder".equals(kind)) return FOLDER_CLASSIC;
        if ("nested_share_folder".equals(kind)) return FOLDER_NSF;
        if ("folder".equals(kind)) return FOLDER_NON_SHARABLE;
        return null;
    }

    static List<String> permissionsForFolderType(String folderType) {
        if (FOLDER_CLASSIC.equals(folderType)) return CLASSIC_PERMISSIONS;
        if (FOLDER_NSF.equals(folderType)) return NSF_PERMISSIONS;
        return Collections.emptyList();
    }

    static String permissionLabel(String folderType, String permission) {
        String label = FOLDER_CLASSIC.equals(folderType)
                ? CLASSIC_PERMISSION_LABELS.get(permission) : NSF_PERMISSION_LABELS.get(permission);
        return label != null ? label : permission;
    }

    /** The nsf-share-folder role for an NSF permission code. */
    static String nsfRole(String permission) {
        return NSF_ROLES.get(permission);
    }

    /** Folder membership id ("uid:CODE") for one user or team, from tree permission codes. */
    private static String folderEntitlementIdFromTreePerms(KeeperFolder folder, List<String> perms) {
        if (FOLDER_NON_SHARABLE.equals(folder.folderType)) return null;
        if (FOLDER_CLASSIC.equals(folder.folderType)) {
            boolean mu = perms.contains("MU");
            boolean mr = perms.contains("MR");
            String code = mu && mr ? "MUR" : mu ? "MU" : mr ? "MR" : "NP";
            return folder.uid + ":" + code;
        }
        // NSF: highest role wins; owner maps to full manager.
        String code = null;
        if (perms.contains("FM") || perms.contains("OW")) code = "FM";
        else if (perms.contains("CSM")) code = "CSM";
        else if (perms.contains("CM")) code = "CM";
        else if (perms.contains("SM")) code = "SM";
        else if (perms.contains("VW")) code = "VW";
        return code == null ? null : folder.uid + ":" + code;
    }

    /** "uid:CODE" -> [uid, CODE]; a raw uid (non-sharable folder) -> [uid, null]. */
    static String[] parseFolderEntitlementId(String identity) {
        int idx = identity.lastIndexOf(':');
        if (idx <= 0 || idx == identity.length() - 1) {
            return new String[] { identity, null };
        }
        return new String[] { identity.substring(0, idx), identity.substring(idx + 1) };
    }

    static Map<String, List<String>> buildUserFolderMap(List<KeeperFolder> folders) {
        Map<String, List<String>> map = new HashMap<String, List<String>>();
        for (KeeperFolder folder : folders) {
            for (Map.Entry<String, List<String>> entry : folder.userPermissions.entrySet()) {
                addToMap(map, entry.getKey(), folderEntitlementIdFromTreePerms(folder, entry.getValue()));
            }
        }
        return map;
    }

    static Map<String, List<String>> buildTeamFolderMap(List<KeeperFolder> folders) {
        Map<String, List<String>> map = new HashMap<String, List<String>>();
        for (KeeperFolder folder : folders) {
            for (Map.Entry<String, List<String>> entry : folder.teamPermissions.entrySet()) {
                addToMap(map, entry.getKey(), folderEntitlementIdFromTreePerms(folder, entry.getValue()));
            }
        }
        return map;
    }

    /** Records under shared, plain and NSF folders (classic and nested records). */
    private static void collectRecords(List<Map<String, Object>> nodes, List<Map<String, Object>> out) {
        for (Map<String, Object> node : nodes) {
            String kind = str(node.get("kind"));
            if (CONTAINER_KINDS.contains(kind) && node.get("children") != null) {
                collectRecords(childrenOf(node), out);
            } else if ("record".equals(kind) || "nested_record".equals(kind)) {
                out.add(node);
            }
        }
    }

    /**
     * Expands vault-tree records into one entitlement per record and
     * permission code ("record_uid:CODE"), listing the users holding it.
     */
    static List<KeeperRecord> getRecordList(Map<String, Object> vaultTree) {
        Map<?, ?> keys = vaultTree.get("share_permissions_key") instanceof Map
                ? (Map<?, ?>) vaultTree.get("share_permissions_key") : Collections.emptyMap();
        Map<?, ?> classic = keys.get("classic") instanceof Map ? (Map<?, ?>) keys.get("classic") : Collections.emptyMap();
        Map<?, ?> nsf = keys.get("nsf") instanceof Map ? (Map<?, ?>) keys.get("nsf") : Collections.emptyMap();
        // MU/MR are folder-share flags in the classic map, not record permissions.
        List<String> classicCodes = new ArrayList<String>();
        for (Object code : classic.keySet()) {
            if (!"MU".equals(code) && !"MR".equals(code)) classicCodes.add(String.valueOf(code));
        }
        List<String> nsfCodes = new ArrayList<String>();
        for (Object code : nsf.keySet()) nsfCodes.add(String.valueOf(code));

        List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>();
        collectRecords(childrenOf(vaultTree.get("tree")), nodes);

        // A record linked into several folders appears once per location;
        // keep the first location and merge the users of the others.
        Map<String, KeeperRecord> records = new LinkedHashMap<String, KeeperRecord>();
        for (Map<String, Object> node : nodes) {
            boolean isClassic = "record".equals(str(node.get("kind")));
            Map<?, ?> labels = isClassic ? classic : nsf;
            List<Map<String, Object>> users = shareEntries(node, "users");
            for (String code : isClassic ? classicCodes : nsfCodes) {
                String recordUid = nvl(str(node.get("uid")));
                KeeperRecord record = records.get(recordUid + ":" + code);
                if (record == null) {
                    record = new KeeperRecord();
                    record.recordUid = recordUid;
                    record.recordUidPerm = recordUid + ":" + code;
                    record.title = nvl(str(node.get("name")));
                    record.recordCategory = isClassic ? "classic" : "nested";
                    record.type = nvl(str(node.get("record_type")));
                    record.path = str(node.get("path"));
                    record.permission = str(labels.get(code));
                    records.put(record.recordUidPerm, record);
                }
                for (Map<String, Object> user : users) {
                    String email = str(user.get("email"));
                    if (toStringList(user.get("permissions")).contains(code) && !record.users.contains(email)) {
                        record.users.add(email);
                    }
                }
            }
        }
        return new ArrayList<KeeperRecord>(records.values());
    }

    static Map<String, List<String>> buildUserRecordMap(List<KeeperRecord> records) {
        Map<String, List<String>> map = new HashMap<String, List<String>>();
        for (KeeperRecord record : records) {
            for (String email : record.users) {
                String key = trimToNull(email);
                if (key != null) addToMap(map, key.toLowerCase(), record.recordUidPerm);
            }
        }
        return map;
    }

    /** Record entitlement ids ("uid:CODE") shared directly with this email. */
    static List<String> getRecordListByEmail(String email, Map<String, Object> vaultTree) {
        return listOrEmpty(buildUserRecordMap(getRecordList(vaultTree)).get(email.trim().toLowerCase()));
    }
}
