# Installing the Keeper Security Connector for IdentityIQ

For IdentityIQ administrators. Allow about 30 minutes.

## 1. Before you start

You need:

- IdentityIQ 8.4, 8.5 or 9.0 (any patch level), with administrator access to
  the server files (or the container) and to the IdentityIQ UI (System
  Administrator capability).
- Lifecycle Manager installed if you want access requests and the Manage
  Accounts page (`import init-lcm.xml` in `iiq console`).
- Keeper Commander Service Mode, reachable from every IdentityIQ server
  (section 2).

## 2. Prepare Keeper Commander Service Mode

The connector sends every request through Keeper Commander Service Mode. Use
Commander's `sailpoint-app-setup` command, which configures everything the
connector needs:

- queue mode (API v2) and the allowed command list (`whoami`, `sync-down`,
  `enterprise-info`, `enterprise-user`, `enterprise-down`, `transfer-user`,
  `share-folder`, `share-record`, `nsf-share-folder`, `nsf-share-record`, `tree`)
- the **transfer target user** that receives a user's vault when IdentityIQ
  deletes the account
- which entitlement types IdentityIQ may change (folders, records, roles, teams)

Steps:

1. Install Keeper Commander on the host that will run Service Mode.
2. Log in with the Keeper service account that IdentityIQ will act as:
   ```
   keeper shell
   login serviceuser@company.com
   ```
3. Run `sailpoint-app-setup` and answer the prompts (port, optional ngrok or
   Cloudflare tunnel, allowed entitlement types, transfer target user).
4. Start Service Mode as described by the command, then check it:
   `curl https://<service-mode-host>/health`
5. Note the **Service Mode URL** (without `/api/v2`) and the **API key**.

Start Service Mode through `sailpoint-app-setup`, not with `service-create`
alone. A Service Mode started with `service-create` has no transfer target:
deletes are accepted but Keeper keeps the user, and the access request stays
Pending / Verifying.

Keep the service account out of IdentityIQ provisioning; the connector
refuses to delete, disable or update it.

## 3. Install the connector

Install on **every IdentityIQ server** in the cluster: the full install on
one server, and `install.sh -n` (or Option C, steps 1 to 5) on the others.

The jar and the settings page must be copied on the server itself: the
IdentityIQ UI cannot install files.

### The IdentityIQ directory (`<identityiq>`)

`<identityiq>` in this guide is the IdentityIQ web application directory:
the directory that contains `WEB-INF`. Pass it to the scripts with `-d`
(`./install.sh -d <identityiq>`, `./uninstall.sh -d <identityiq>`); they
stop with `Not an IdentityIQ directory` if `<identityiq>/WEB-INF/bin/iiq` is
missing.

| IdentityIQ runs on | Typical `<identityiq>` |
|---|---|
| Tomcat on Linux | `<tomcat>/webapps/identityiq`, e.g. `/opt/tomcat/webapps/identityiq` |
| Tomcat on Windows | `<tomcat>\webapps\identityiq`, e.g. `C:\Program Files\Apache Software Foundation\Tomcat 9.0\webapps\identityiq` |
| Official Tomcat container image | `/usr/local/tomcat/webapps/identityiq` (inside the container) |

To find it, locate `iiq.properties` and go up two levels:

```sh
find / -path "*WEB-INF/classes/iiq.properties" 2>/dev/null
# /opt/tomcat/webapps/identityiq/WEB-INF/classes/iiq.properties
#   -> <identityiq> is /opt/tomcat/webapps/identityiq
```

What the installer uses in it:

| Path | Used for |
|---|---|
| `<identityiq>/WEB-INF/lib/` | The connector jar |
| `<identityiq>/define/applications/` | The 8.x settings page (`KeeperAttributesForm.xhtml`) |
| `<identityiq>/WEB-INF/bin/iiq` | `iiq console`, to import the XML files |
| `<identityiq>/WEB-INF/classes/iiq.properties` | The database `iiq console` connects to |

### Choose an install option

| IdentityIQ runs on | Use |
|---|---|
| A Linux or macOS server | Option A |
| A container (e.g. Docker) | Option B |
| Windows, or no shell access to run scripts | Option C |

### Option A: install script (Linux / macOS)

Run as the user that owns the IdentityIQ files. The script uses
`iiq console`, which connects to the database configured in
`WEB-INF/classes/iiq.properties`.

```sh
unzip keeper-iiq-connector-*.zip
cd keeper-iiq-connector-*/
./install.sh -d <identityiq>
```

In one run, the script:

1. backs up any previous version and copies the jar and the settings page
2. registers the **Keeper Security** application type and the
   **Keeper Entitlement Refresh** rule (section 5)
3. creates the **Keeper Security** application with its email correlation
   (section 4); an existing application of that name is kept with its settings
4. creates or updates its three aggregation tasks (section 5)

Options:

- `-a "My Keeper App"`: use another application name.
- `-n`: only install the files and the application type; skip the
  application and tasks. Use it on the other servers of a cluster, or to
  create the application in the UI.

Restart the application server afterwards (e.g. `sudo systemctl restart tomcat`,
or `<tomcat>/bin/shutdown.sh` then `<tomcat>/bin/startup.sh`), then configure
the application (section 4, last step). Sections 4 and 5 describe what the
script did; follow them only if you install manually.

### Option B: IdentityIQ in a container (e.g. Docker)

Run the script inside the container that runs Tomcat, so that `iiq console`
reaches the database configured in its `iiq.properties`. In the commands
below, `<container>` is that container's name and `<identityiq>` the
webapp directory inside it (in the official Tomcat images,
`/usr/local/tomcat/webapps/identityiq`; see
[The IdentityIQ directory](#the-identityiq-directory-identityiq)). If that
directory is mounted from the host, use the path inside the container, not
the host path.

1. Copy the zip into the container and open a shell in it:
   ```sh
   docker cp keeper-iiq-connector-*.zip <container>:/opt/
   docker exec -it <container> bash
   ```
2. Unpack and install. If the container has no `unzip`, use the JDK's `jar`
   tool; it does not keep execute permissions, so start the script with `sh`:
   ```sh
   cd /opt
   jar xf keeper-iiq-connector-*.zip
   cd keeper-iiq-connector-*/
   sh ./install.sh -d <identityiq>
   exit
   ```
3. Restart the container: `docker restart <container>`.

The same options apply (`-a`, `-n`). The jar and the settings page are
written into `<identityiq>`. If that directory is not on a mounted volume,
they are lost when the container is re-created: install again, or build them
into your image.

### Option C: manual (any OS, including Windows)

1. Stop the application server.
2. Delete any older `keeper-iiq-connector*.jar` from `<identityiq>/WEB-INF/lib`.
3. Copy `lib/keeper-iiq-connector-*.jar` to `<identityiq>/WEB-INF/lib/`.
4. Copy `web/define/applications/KeeperAttributesForm.xhtml` to
   `<identityiq>/define/applications/`.
5. Start the application server.
6. Register the application type by importing `config/Keeper.xml`, either:
   - in the UI: **Gear icon → Global Settings → Import from File**, choose
     `config/Keeper.xml`, **Import**; or
   - with `iiq console` (`iiq.bat console` on Windows):
     ```
     cd <identityiq>/WEB-INF/bin
     iiq console
     > import /full/path/to/config/Keeper.xml
     > quit
     ```
7. Import `config/Keeper-Entitlement-Refresh.xml` the same way.
8. Create the application (section 4) and its tasks (section 5). The four
   files are imported in this order: `Keeper.xml`,
   `Keeper-Entitlement-Refresh.xml`, `Keeper-Application.xml`,
   `Keeper-Tasks.xml`.

Put the jar in `WEB-INF/lib`. Loading it only through an application's
`connector-classpath` is not enough: the application definition pages load
the connector class directly and stay blank if they cannot find it.

## 4. Create the application

`install.sh` creates the application (unless run with `-n`); in that case only
enter its settings (last step of Option A).

### Option A: import the application

`config/Keeper-Application.xml` creates an application named
**Keeper Security** (owner `spadmin`) with an email correlation
(**Keeper Security Email Correlation**). Import it after the application
type (section 3), either:

- in the UI: **Gear icon → Global Settings → Import from File**, choose
  `config/Keeper-Application.xml`, **Import**; or
- with `iiq console`:
  ```
  cd <identityiq>/WEB-INF/bin
  iiq console
  > import /full/path/to/config/Keeper-Application.xml
  > quit
  ```

To use another owner, edit the `<Owner>` reference first. For another name,
use `install.sh -a "<name>"` instead. Importing replaces an existing
application of the same name, including its settings.

Then open the application (**Applications → Application Definition**), enter
the **Service Mode URL** and **API Key** on its settings page, click
**Test Connection**, and **Save**.

### Option B: create it in the UI

1. **Applications → Application Definition → Add New Application.**
2. **Name:** e.g. `Keeper Security`. **Owner:** an administrator.
   **Application Type:** `Keeper Security`.
3. On the settings page (**Configuration → Settings** in 8.x, **Settings** in
   9.0), enter the **Service Mode URL** and **API Key**.
   Leave Poll Timeout (60) and Sync Cache TTL (30) unless commands time out.
4. Click **Test Connection**. It should report success.
5. **Correlation** tab: create an account correlation that matches the
   account attribute `email` to the identity attribute `email`, so Keeper
   accounts join existing identities.
6. **Save.**

The application comes with the account schema, the five entitlement schemas
and a **Create** provisioning policy: email and name are taken from the
identity, and the node defaults to the enterprise root node (a dropdown lists
all nodes).

## 5. Create the aggregation tasks

`install.sh` creates the tasks (unless run with `-n`). Manually, import
`config/Keeper-Tasks.xml`, in the UI (**Gear icon → Global Settings →
Import from File**) or with `iiq console`. Its tasks are for the
**Keeper Security** application; for an application with another name, run
`install.sh -d <identityiq> -a "<name>"`.

This creates:

| Task | What it does |
|---|---|
| `<name> Entitlement Aggregation` | Nodes, teams, roles, folders and records. Names changed in Keeper are applied to existing entitlements (through the **Keeper Entitlement Refresh** rule), and entitlements that no longer exist in Keeper are removed from IdentityIQ. |
| `<name> Account Aggregation` | Keeper users and their entitlements. Accounts of users no longer in Keeper are removed from IdentityIQ. |
| `<name> Full Aggregation` | Runs the two above in order, then IdentityIQ's **Full Text Index Refresh** and **Perform Identity Request Maintenance**; stops at the first task that fails |

Run **`<name> Full Aggregation`** (Setup → Tasks → Run Now). Schedule it to
keep IdentityIQ current, e.g. nightly.

Keeper accounts that do not correlate to an existing identity create new
identities. Their email stays blank unless an identity mapping sets it. In a
test environment without an HR or other authoritative source, map it from
Keeper: **Global Settings → Identity Mappings → email → Add Source**,
application **Keeper Security**, attribute **email**. Then run **Refresh
Identity Cube**.

Full Aggregation ends with **Full Text Index Refresh**, so new Keeper
entitlements can be found in Manage User Access. If you run Entitlement
Aggregation on its own, run Full Text Index Refresh after it.

### When access requests complete

After an access request is provisioned, the connector returns the user's
updated Keeper account, so IdentityIQ shows the new access at once and no
aggregation is needed. The request itself stays in **Verifying** until
IdentityIQ's **Perform Identity Request Maintenance** task checks it and
marks it complete. That task runs at the end of Full Aggregation and, by
default, once a day at 2 AM.

To close requests sooner, edit that task's schedule (Setup → Tasks →
Scheduled Tasks → **Perform Identity Request Maintenance**), e.g. every
15 minutes. It checks open requests for all applications and is quick
when there are few.

## 6. Check the installation

| Check | Expected |
|---|---|
| Test Connection | Success |
| Full Aggregation | Succeeds. Account Aggregation may end with a warning that no authoritative application exists; that is expected when Keeper is the only application. |
| Applications → Entitlement Catalog, filtered on the application | Nodes, teams, roles, folders `Name [Permission]`, records `Title [Permission]` |
| Identity → Entitlements tab of a Keeper user | Their node, roles, teams, folders and records |
| Manage User Access → add a team to a test user | The user is in the team in the Keeper Admin Console |

## 7. Upgrade

Install the new package the same way (script or manual steps). The application,
its settings and aggregated data are kept; `install.sh` updates its tasks to the
new version. If you install manually, import `config/Keeper-Tasks.xml` again so
the tasks get the current settings (for example, removing accounts and
entitlements deleted in Keeper). Restart the application server on every node.

If an existing Keeper application has a `connector-classpath` entry pointing to
an older connector jar, remove that entry (and the old jar). Otherwise
IdentityIQ keeps loading the old connector for that application.

## 8. Uninstall

Nothing is changed in Keeper. Repeat the file removal on every IdentityIQ
server in the cluster.

### Option A: uninstall script (Linux / macOS / container)

```sh
cd keeper-iiq-connector-*/
./uninstall.sh -d <identityiq>
```

`<identityiq>` is the same IdentityIQ directory as for the install (see
[The IdentityIQ directory](#the-identityiq-directory-identityiq)).

In a container, run it inside the container as in section 3, Option B
(`sh ./uninstall.sh -d <identityiq>`), then restart the container.

After confirmation (skip it with `-y`), the script removes:

- every application of type **Keeper Security**, with its accounts and
  entitlements, its tasks (the three aggregation tasks, and any other task
  whose name starts with the application name or that aggregates it) and
  their results, and the identity mapping sources that read from it
- the **Keeper Security Email Correlation**
- the **Keeper Entitlement Refresh** rule
- the **Keeper Security** application type
- the connector jar, the settings page and earlier install backups

Restart the application server afterwards. On other servers in the cluster,
remove only the files (Option B, step 5).

### Option B: manual

1. **Global Settings → Identity Mappings:** remove any source that reads from
   a Keeper Security application.
2. **Setup → Tasks:** delete each Keeper Security application's tasks (Full
   Aggregation first, as it runs the other two).
3. **Applications → Application Definition:** delete the application. This
   removes its accounts and entitlements from IdentityIQ.
4. Delete **Keeper Security Email Correlation** if you imported it
   (`iiq console`: `delete CorrelationConfig "Keeper Security Email Correlation"`),
   and the rule (`delete Rule "Keeper Entitlement Refresh"`).
5. Remove `WEB-INF/lib/keeper-iiq-connector-*.jar` and
   `define/applications/KeeperAttributesForm.xhtml`.
6. Restart the application server.

The manual steps leave the **Keeper Security** application type registered;
it is harmless without the jar. Only `uninstall.sh` removes it.

Identities created by aggregation are kept either way. Run **Prune Identity
Cubes** to remove those left without accounts.

## 9. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Test Connection: `status 401` | Wrong API key. Copy it again from Service Mode. |
| Test Connection: `status 403` | `whoami` is not allowed. Set up Service Mode with `sailpoint-app-setup`. |
| `ClassNotFoundException: com.keeper.identityiq.KeeperConnector`, or the application's Schema page is blank | The jar is missing from `WEB-INF/lib` on this server. |
| IdentityIQ 9.0: the Settings page shows no fields | The jar is missing or older than this version, or the application was created from another application type. Install this package, restart, and check the application's type is **Keeper Security**. |
| Tasks named `__APP_NAME__ ...` | A template was imported unchanged. Delete those tasks and import `config/Keeper-Tasks.xml`, or run `install.sh -a "<name>"`. |
| `Keeper API poll timed out` | Commander is slow or busy. Increase Poll Timeout. |
| A delete succeeds but the user still exists in Keeper (not locked), and the access request stays Pending / Verifying | Service Mode has no transfer target, for example because it was started with `service-create` instead of `sailpoint-app-setup`. Set it up with `sailpoint-app-setup` (with a transfer target email), check that Account Transfer is enabled for the user's role, then delete again. The next Full Aggregation removes the account from IdentityIQ and completes the request. |
| An entitlement renamed in Keeper keeps its old name in IdentityIQ | IdentityIQ sets an entitlement's display name only when it creates it; the **Keeper Entitlement Refresh** rule updates it on each Entitlement Aggregation. Tasks created before this version do not use the rule: run `install.sh` again (or import `config/Keeper-Entitlement-Refresh.xml` and `config/Keeper-Tasks.xml`), then run Full Aggregation. Display names edited in the Entitlement Catalog are replaced by Keeper's names. |
| Accounts or entitlements disappear from IdentityIQ after an aggregation | They were removed in Keeper (user removed, team or role deleted, share revoked); the aggregation tasks remove them from IdentityIQ too. Identities left without accounts stay until **Prune Identity Cubes** runs. |
| Roles or teams missing right after create | Keeper applies them once the user accepts the invitation. |
| An access request stays "Verifying" | Normal until Perform Identity Request Maintenance runs (see [When access requests complete](#when-access-requests-complete)). Roles and teams for an invited user are only verified after they accept the invitation and the next aggregation runs. |
| A user cannot log in to the vault after a node change | The new node uses SSO and the user is not set up in that identity provider. |
| Entitlements are not found in Manage User Access | Run Full Text Index Refresh, and check the entitlements are Requestable in the Entitlement Catalog. |
| Manage User Access → Remove Access shows no current entitlements | The Account Aggregation task must have **Refresh assigned, detected roles and promote additional entitlements** checked (the shipped task does). For existing identities, run **Refresh Identity Cube** with the same option checked. |

Errors from Keeper are shown on the access request (My Work → Access Requests →
Details) and in the application server log. To also log each aggregation's
object count, set the log level of `com.keeper.identityiq` to `info` in
`WEB-INF/classes/log4j2.properties`.
