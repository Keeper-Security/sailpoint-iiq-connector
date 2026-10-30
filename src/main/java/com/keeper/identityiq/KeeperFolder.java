package com.keeper.identityiq;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A classic shared folder, NSF folder or plain (non-sharable) vault folder. */
final class KeeperFolder {
    String uid;
    String name;
    String path;
    String folderType;
    String parentId;
    /** lowercase email -> tree permission codes */
    final Map<String, List<String>> userPermissions = new LinkedHashMap<String, List<String>>();
    /** team uid -> tree permission codes */
    final Map<String, List<String>> teamPermissions = new LinkedHashMap<String, List<String>>();
}
