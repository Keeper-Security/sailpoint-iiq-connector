package com.keeper.identityiq;

import java.util.ArrayList;
import java.util.List;

/** One record entitlement: a record at one permission level. */
final class KeeperRecord {
    String recordUid;
    String recordUidPerm;
    String title;
    String recordCategory;
    String type;
    String path;
    String permission;
    final List<String> users = new ArrayList<String>();
}
