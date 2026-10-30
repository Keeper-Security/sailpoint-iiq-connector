import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.keeper.identityiq.KeeperConnector;

import sailpoint.object.Application;
import sailpoint.object.AttributeDefinition;
import sailpoint.object.ProvisioningPlan;
import sailpoint.object.ProvisioningPlan.AccountRequest;
import sailpoint.object.ProvisioningPlan.AttributeRequest;
import sailpoint.object.ProvisioningPlan.Operation;
import sailpoint.object.ProvisioningResult;
import sailpoint.object.ResourceObject;
import sailpoint.tools.CloseableIterator;

/**
 * Offline functional tests for KeeperConnector against mock_service_mode.py.
 *
 * Usage: java KeeperConnectorTest <mock-url> <command-log> [full|whoami-403]
 * Exits with status 1 if any check fails.
 */
public class KeeperConnectorTest {

    private static final Pattern FOLDER_ID = Pattern.compile("^[A-Za-z0-9_-]+(:(NP|MU|MR|MUR|VW|SM|CM|CSM|FM))?$");
    private static final Pattern RECORD_ID = Pattern.compile("^[A-Za-z0-9_-]+:[A-Z]{2,3}$");
    private static final String SERVICE_ACCOUNT = "service.account@example.com";

    private static String logPath;
    private static int passed;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) throws Exception {
        String url = args[0];
        logPath = args[1];
        String mode = args.length > 2 ? args[2] : "full";

        if ("whoami-403".equals(mode)) {
            testConnectionFailsWhenWhoamiBlocked(url);
        } else {
            runFullSuite(url);
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failures.size() + " failed");
        for (String failure : failures) System.out.println("  FAILED: " + failure);
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    // ------------------------------------------------------------------
    // Suites
    // ------------------------------------------------------------------

    private static void testConnectionFailsWhenWhoamiBlocked(String url) throws Exception {
        section("Test connection when whoami is not permitted");
        try {
            connector(url, "test-key").testConfiguration();
            check("test connection fails", false, "no exception");
        } catch (Exception e) {
            check("test connection fails with HTTP 403 message",
                    e.getMessage().contains("status 403"), e.getMessage());
        }
    }

    private static void runFullSuite(String url) throws Exception {
        KeeperConnector c = connector(url, "test-key");

        section("Settings fields (IdentityIQ 9.0 Settings page)");
        Map<String, AttributeDefinition> settings = new LinkedHashMap<String, AttributeDefinition>();
        for (AttributeDefinition def : c.getDefaultAttributes()) settings.put(def.getName(), def);
        check("settings are serviceUrl, apiKey, pollTimeoutSeconds, syncCacheTtlSeconds",
                settings.keySet().equals(new HashSet<String>(Arrays.asList(
                        "serviceUrl", "apiKey", "pollTimeoutSeconds", "syncCacheTtlSeconds"))), settings.keySet());
        check("API key is a secret field", settings.containsKey("apiKey")
                && AttributeDefinition.TYPE_SECRET.equals(settings.get("apiKey").getType()), settings.get("apiKey"));
        check("URL and API key are required", settings.containsKey("serviceUrl") && settings.get("serviceUrl").isRequired()
                && settings.containsKey("apiKey") && settings.get("apiKey").isRequired(), settings.keySet());

        section("Test connection");
        clearLog();
        c.testConfiguration();
        check("test connection runs whoami", commands().contains("whoami"), commands());

        section("Wrong API key");
        try {
            connector(url, "wrong-key").testConfiguration();
            check("wrong API key is rejected", false, "no exception");
        } catch (Exception e) {
            check("wrong API key is rejected with HTTP 401", e.getMessage().contains("status 401"), e.getMessage());
        }

        section("Aggregation");
        Map<String, List<ResourceObject>> all = new LinkedHashMap<String, List<ResourceObject>>();
        for (String type : new String[] {"node", "team", "role", "folder", "record", "account"}) {
            clearLog();
            all.put(type, iterate(c, type));
            check(type + " aggregation returns objects", !all.get(type).isEmpty(), "0 objects");
            check(type + " identities are unique", unique(all.get(type)), "duplicate identity");
            List<String> sent = commands();
            check(type + " aggregation syncs enterprise and vault first",
                    sent.indexOf("enterprise-down -f") >= 0 && sent.indexOf("sync-down -f") >= 0, sent);
        }
        check("folder ids are uid or uid:CODE", badIds(all.get("folder"), FOLDER_ID).isEmpty(),
                badIds(all.get("folder"), FOLDER_ID));
        check("record ids are uid:CODE", badIds(all.get("record"), RECORD_ID).isEmpty(),
                badIds(all.get("record"), RECORD_ID));

        int disabledMismatch = 0;
        for (ResourceObject account : all.get("account")) {
            boolean shouldBeDisabled = !"Active".equals(account.getAttribute("status"));
            if (shouldBeDisabled != Boolean.TRUE.equals(account.getAttribute("IIQDisabled"))) disabledMismatch++;
        }
        check("accounts are disabled exactly when status is not Active", disabledMismatch == 0,
                disabledMismatch + " mismatches");

        ResourceObject target = pickTargetAccount(all.get("account"));
        check("found an Active test account with folders and records", target != null, "none");
        if (target == null) return;
        String id = target.getIdentity();
        String email = (String) target.getAttribute("email");
        System.out.println("  test account: " + id + " " + email);

        section("Single account read");
        ResourceObject read = c.getObject("account", id, null);
        check("getObject by user id returns the account", read != null && id.equals(read.getIdentity()), "null");
        check("getObject returns the same folders", read != null
                && String.valueOf(target.getAttribute("folders")).equals(String.valueOf(read.getAttribute("folders"))),
                "different folders");
        String folderId = all.get("folder").get(0).getIdentity();
        check("getObject returns a folder entitlement", c.getObject("folder", folderId, null) != null, folderId);

        section("Create account");
        ProvisioningResult r = provision(c, req(AccountRequest.Operation.Create, null,
                attr("email", Operation.Set, "new.user@example.com"),
                attr("name", Operation.Set, "New User"),
                attr("node", Operation.Set, Arrays.asList("1000000000000002")),
                attr("jobTitle", Operation.Set, "Engineer"),
                attr("roles", Operation.Add, Arrays.asList("1000000000000005")),
                attr("teams", Operation.Add, Arrays.asList("mockuid000000000000001"))));
        expectCommand("create sends enterprise-user --add with all fields",
                "enterprise-user \"new.user@example.com\" --add --name \"New User\" --job-title \"Engineer\" "
                + "--node \"1000000000000002\" --add-role \"1000000000000005\" --add-team \"mockuid000000000000001\"");
        check("create is committed", committed(r), status(r));
        check("create returns the invited account as disabled", r.getObject() != null
                && Boolean.TRUE.equals(r.getObject().getAttribute("IIQDisabled")), "no object");

        r = provision(c, req(AccountRequest.Operation.Create, null,
                attr("email", Operation.Set, "x@example.com"), attr("name", Operation.Set, "X")));
        check("create without node fails", failed(r) && errors(r).contains("missing required attribute \"node\""),
                errors(r));
        check("create without node sends no command", mutations().isEmpty(), mutations());

        section("Modify roles, teams, node, job title");
        List<?> roles = (List<?>) target.getAttribute("roles");
        String droppedRole = roles.isEmpty() ? null : String.valueOf(roles.get(0));
        List<Object> keptRoles = new ArrayList<Object>(roles.isEmpty() ? roles : roles.subList(1, roles.size()));
        r = provision(c, req(AccountRequest.Operation.Modify, id,
                attr("roles", Operation.Set, keptRoles),
                attr("teams", Operation.Add, Arrays.asList("mockuid000000000000002")),
                attr("node", Operation.Set, "1000000000000001"),
                attr("jobTitle", Operation.Remove, null)));
        String expected = "enterprise-user \"" + email + "\" -f --job-title \"\" --node \"1000000000000001\""
                + (droppedRole == null ? "" : " --remove-role \"" + droppedRole + "\"")
                + " --add-team \"mockuid000000000000002\"";
        expectCommand("modify sends one enterprise-user command (removes before adds)", expected);
        check("modify is committed", committed(r), status(r));

        section("Modify folders");
        String classicMR = null, nsfVW = null, nonSharable = null;
        for (ResourceObject f : all.get("folder")) {
            Object type = f.getAttribute("folderType");
            if (classicMR == null && "classic".equals(type) && "MR".equals(f.getAttribute("permission"))) classicMR = f.getIdentity();
            if (nsfVW == null && "nsf".equals(type) && "VW".equals(f.getAttribute("permission"))) nsfVW = f.getIdentity();
            if (nonSharable == null && "non-sharable".equals(type)) nonSharable = f.getIdentity();
        }
        String classicUid = classicMR.split(":")[0];
        String nsfUid = nsfVW.split(":")[0];
        r = provision(c, req(AccountRequest.Operation.Modify, id,
                attr("folders", Operation.Add, Arrays.asList(classicMR, nsfVW, nsfUid + ":MUR", nonSharable))));
        List<String> sent = mutations();
        check("classic folder grant uses share-folder -o off -p on", sent.contains(
                "share-folder -a grant -e \"" + email + "\" -o off -p on \"" + classicUid + "\""), sent);
        check("NSF folder grant uses nsf-share-folder -r viewer", sent.contains(
                "nsf-share-folder -a grant -e \"" + email + "\" -r viewer \"" + nsfUid + "\""), sent);
        check("invalid NSF permission is reported", errors(r).contains("invalid folder entitlement \"" + nsfUid + ":MUR\""),
                errors(r));
        check("non-sharable folder grant is reported", errors(r).contains("cannot grant share on non-sharable folder"),
                errors(r));
        check("partial folder failure marks the request failed", failed(r), status(r));

        r = provision(c, req(AccountRequest.Operation.Modify, id,
                attr("folders", Operation.Remove, Arrays.asList(classicMR, nsfVW))));
        sent = mutations();
        check("classic folder remove uses share-folder -a remove", sent.contains(
                "share-folder -a remove -e \"" + email + "\" -f \"" + classicUid + "\""), sent);
        check("NSF folder remove uses nsf-share-folder -a remove", sent.contains(
                "nsf-share-folder -a remove -e \"" + email + "\" \"" + nsfUid + "\""), sent);

        r = provision(c, req(AccountRequest.Operation.Modify, id,
                attr("folders", Operation.Add, Arrays.asList(classicUid + ":MUR")),
                attr("folders", Operation.Remove, Arrays.asList(classicMR))));
        sent = mutations();
        check("permission change grants the new level only", sent.size() == 1 && sent.get(0).equals(
                "share-folder -a grant -e \"" + email + "\" -o on -p on \"" + classicUid + "\""), sent);

        section("Modify records");
        r = provision(c, req(AccountRequest.Operation.Modify, id,
                attr("records", Operation.Add, Arrays.asList("REC1:RO", "REC1:CE", "REC1:CS", "REC2:VW", "REC2:SM",
                        "REC2:CM", "REC2:CSM", "REC2:FM", "REC1:OW", "nosuchrecord:RO"))));
        sent = mutations();
        String e = "-e \"" + email + "\"";
        check("record RO grant", sent.contains("share-record " + e + " \"REC1\" --action grant"), sent);
        check("record CE grant", sent.contains("share-record " + e + " \"REC1\" --write --action grant"), sent);
        check("record CS grant", sent.contains("share-record " + e + " \"REC1\" --share --action grant"), sent);
        check("NSF record VW grant", sent.contains("nsf-share-record " + e + " \"REC2\" -r viewer --action grant"), sent);
        check("NSF record SM grant", sent.contains("nsf-share-record " + e + " \"REC2\" -r share-manager --action grant"), sent);
        check("NSF record CM grant", sent.contains("nsf-share-record " + e + " \"REC2\" -r content-manager --action grant"), sent);
        check("NSF record CSM grant", sent.contains("nsf-share-record " + e + " \"REC2\" -r content-share-manager --action grant"), sent);
        check("NSF record FM grant", sent.contains("nsf-share-record " + e + " \"REC2\" -r full-manager --action grant"), sent);
        check("owner change is refused without a command", errors(r).contains("Ownership change is restricted")
                && !String.valueOf(sent).contains("REC1:OW"), errors(r));
        check("Keeper error is reported per record", errors(r).contains("nosuchrecord:RO"), errors(r));

        r = provision(c, req(AccountRequest.Operation.Modify, id,
                attr("records", Operation.Remove, Arrays.asList("REC1:RO"))));
        expectCommand("record revoke", "share-record " + e + " \"REC1\" --action revoke");

        section("Email rename and input validation");
        r = provision(c, req(AccountRequest.Operation.Modify, id, attr("email", Operation.Set, "renamed@example.com")));
        expectCommand("email rename uses --add-alias",
                "enterprise-user \"" + email + "\" -f --add-alias \"renamed@example.com\"");
        r = provision(c, req(AccountRequest.Operation.Modify, id,
                attr("name", Operation.Set, "Evil\" --add-role \"1\nwhoami")));
        check("value with a line break is refused", failed(r) && errors(r).contains("control character"), errors(r));
        check("refused value sends no command", mutations().isEmpty(), mutations());
        r = provision(c, req(AccountRequest.Operation.Modify, id, attr("name", Operation.Set, "Quote \" Name")));
        expectCommand("quotes are escaped", "enterprise-user \"" + email + "\" -f --name \"Quote \\\" Name\"");
        r = provision(c, req(AccountRequest.Operation.Modify, id, attr("aliases", Operation.Add, "a@example.com")));
        check("aliases cannot be managed", failed(r) && errors(r).contains("aliases"), errors(r));

        section("Disable and enable");
        r = provision(c, req(AccountRequest.Operation.Disable, id));
        expectCommand("disable sends --lock", "enterprise-user \"" + email + "\" --lock");
        check("disabled account is returned as disabled", r.getObject() != null
                && Boolean.TRUE.equals(r.getObject().getAttribute("IIQDisabled")), "not disabled");
        r = provision(c, req(AccountRequest.Operation.Enable, id));
        expectCommand("enable sends --unlock", "enterprise-user \"" + email + "\" --unlock");
        check("enabled account is returned as enabled", r.getObject() != null
                && Boolean.FALSE.equals(r.getObject().getAttribute("IIQDisabled")), "not enabled");

        section("Service account protection");
        for (AccountRequest.Operation op : new AccountRequest.Operation[] {
                AccountRequest.Operation.Disable, AccountRequest.Operation.Delete}) {
            r = provision(c, req(op, SERVICE_ACCOUNT));
            check(op + " of the service account is refused", failed(r) && errors(r).contains("service account"),
                    errors(r));
            check(op + " of the service account sends no command", mutations().isEmpty(), mutations());
        }
        r = provision(c, req(AccountRequest.Operation.Modify, SERVICE_ACCOUNT,
                attr("roles", Operation.Add, Arrays.asList("1000000000000005"))));
        check("update of the service account is refused", failed(r) && errors(r).contains("service account"), errors(r));

        section("Delete");
        r = provision(c, req(AccountRequest.Operation.Delete, id));
        expectCommand("delete sends transfer-user", "transfer-user \"" + email + "\" -f");
        check("delete is committed", committed(r), status(r));
        r = provision(c, req(AccountRequest.Operation.Delete, "123"));
        check("delete of an unknown user succeeds", committed(r), status(r));
        check("delete of an unknown user sends no command", mutations().isEmpty(), mutations());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static KeeperConnector connector(String url, String key) {
        Application app = new Application();
        app.setName("Keeper Test");
        app.setAttribute("serviceUrl", url);
        app.setAttribute("apiKey", key);
        app.setAttribute("syncCacheTtlSeconds", "0");
        return new KeeperConnector(app);
    }

    private static List<ResourceObject> iterate(KeeperConnector c, String type) throws Exception {
        List<ResourceObject> list = new ArrayList<ResourceObject>();
        CloseableIterator<ResourceObject> it = c.iterateObjects(type, null, null);
        while (it.hasNext()) list.add(it.next());
        it.close();
        return list;
    }

    private static ResourceObject pickTargetAccount(List<ResourceObject> accounts) {
        for (ResourceObject a : accounts) {
            if ("Active".equals(a.getAttribute("status")) && !SERVICE_ACCOUNT.equals(a.getAttribute("email"))
                    && !((List<?>) a.getAttribute("folders")).isEmpty()
                    && !((List<?>) a.getAttribute("records")).isEmpty()) {
                return a;
            }
        }
        return null;
    }

    private static List<String> badIds(List<ResourceObject> objects, Pattern format) {
        List<String> bad = new ArrayList<String>();
        for (ResourceObject o : objects) {
            if (!format.matcher(o.getIdentity()).matches()) bad.add(o.getIdentity());
        }
        return bad;
    }

    private static boolean unique(List<ResourceObject> objects) {
        Set<String> seen = new HashSet<String>();
        for (ResourceObject o : objects) {
            if (!seen.add(o.getIdentity())) return false;
        }
        return true;
    }

    private static AttributeRequest attr(String name, Operation op, Object value) {
        return new AttributeRequest(name, op, value);
    }

    private static AccountRequest req(AccountRequest.Operation op, String nativeId, AttributeRequest... attrs) {
        AccountRequest r = new AccountRequest(op, "Keeper Test", null, nativeId);
        for (AttributeRequest a : attrs) r.add(a);
        return r;
    }

    private static ProvisioningResult provision(KeeperConnector c, AccountRequest request) throws Exception {
        clearLog();
        ProvisioningPlan plan = new ProvisioningPlan();
        plan.add(request);
        c.provision(plan);
        return request.getResult();
    }

    private static boolean committed(ProvisioningResult r) {
        return r != null && ProvisioningResult.STATUS_COMMITTED.equals(r.getStatus());
    }

    private static boolean failed(ProvisioningResult r) {
        return r != null && ProvisioningResult.STATUS_FAILED.equals(r.getStatus());
    }

    private static String status(ProvisioningResult r) {
        return r == null ? "no result" : r.getStatus() + " " + errors(r);
    }

    private static String errors(ProvisioningResult r) {
        return r == null || r.getErrors() == null ? "" : String.valueOf(r.getErrors());
    }

    private static List<String> commands() throws Exception {
        return Files.readAllLines(Paths.get(logPath));
    }

    /** Commands that change Keeper (reads and syncs filtered out). */
    private static List<String> mutations() throws Exception {
        List<String> out = new ArrayList<String>();
        for (String line : commands()) {
            if (!line.startsWith("enterprise-info") && !line.startsWith("tree ") && !line.equals("whoami")
                    && !line.startsWith("enterprise-down") && !line.startsWith("sync-down")) {
                out.add(line);
            }
        }
        return out;
    }

    private static void expectCommand(String name, String expected) throws Exception {
        List<String> sent = mutations();
        check(name, sent.size() == 1 && sent.get(0).equals(expected), "expected [" + expected + "] got " + sent);
    }

    private static void clearLog() throws Exception {
        Files.write(Paths.get(logPath), new byte[0]);
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title);
    }

    private static void check(String name, boolean ok, Object detail) {
        if (ok) {
            passed++;
            System.out.println("  PASS " + name);
        } else {
            failures.add(name + " -> " + detail);
            System.out.println("  FAIL " + name + " -> " + detail);
        }
    }
}
