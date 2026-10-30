package com.keeper.identityiq;

import java.util.Arrays;
import java.util.List;

/** The connector's object types (account schema and entitlement schemas). */
final class ObjectTypes {

    static final String TYPE_ACCOUNT = "account";
    static final String TYPE_NODE = "node";
    static final String TYPE_TEAM = "team";
    static final String TYPE_ROLE = "role";
    static final String TYPE_FOLDER = "folder";
    static final String TYPE_RECORD = "record";
    static final List<String> SUPPORTED_TYPES = Arrays.asList(
            TYPE_ACCOUNT, TYPE_NODE, TYPE_TEAM, TYPE_ROLE, TYPE_FOLDER, TYPE_RECORD);

    private ObjectTypes() {
    }
}
