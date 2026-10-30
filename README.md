# Keeper Security Connector for SailPoint IdentityIQ

IdentityIQ 8.4, 8.5 and 9.0 · Java 11 or later

Governs Keeper Security enterprise users and their access from SailPoint
IdentityIQ. The connector talks to Keeper only through **Keeper Commander
Service Mode (API v2)**, which runs in your own infrastructure, so vault data
stays end-to-end encrypted and no secret values are ever read.

## Features

| Feature | Supported |
|---|---|
| Test connection | Yes |
| Account aggregation | Yes |
| Single account refresh | Yes |
| Entitlement (group) aggregation: node, team, role, folder, record | Yes |
| Create account (invite) | Yes |
| Update account: node, teams, roles, folder shares, record shares, name, job title, email | Yes |
| Enable / disable (Keeper unlock / lock) | Yes |
| Delete (vault transfer, then removal) | Yes |
| Password management, pass-through authentication | No |
| Delta aggregation, partitioning | No |

IdentityIQ features declared: `PROVISIONING, SYNC_PROVISIONING, ENABLE, UNLOCK`.

Aggregation keeps IdentityIQ in step with Keeper: changes made in the Keeper
vault or Admin Console (names, memberships, shares, status) appear after the
next aggregation, including renamed records, folders, teams, roles and nodes,
and the shipped aggregation tasks remove accounts and entitlements that no
longer exist in Keeper.

## Requirements

- SailPoint IdentityIQ 8.4, 8.5 or 9.0, including their patches (tested on
  8.4, 8.4p4, 8.5, 8.5p2 and 9.0; built against 8.5p2). Use the Java,
  application server and database versions your IdentityIQ release supports;
  9.0 needs Tomcat 10.1 or 11 and MySQL 8.4.
- Keeper Commander running in **Service Mode** with queue mode (API v2), set up
  with the `sailpoint-app-setup` command (see [INSTALL.md](INSTALL.md)). Tested
  with Commander 18.1.8.
- Network access from the IdentityIQ servers to the Service Mode URL (HTTPS).
- A Keeper service account with rights to manage enterprise users, teams,
  roles and the folders and records you want to govern.

The connector has no third-party dependencies: it uses only the JDK and the
libraries shipped with IdentityIQ.

## How Keeper maps to IdentityIQ

### Account (object type `account`)

| Attribute | Notes |
|---|---|
| `userId` | Keeper enterprise user ID. Native identity of the account. |
| `email` | Primary email; used for correlation and as the Keeper login. Required on create. |
| `name` | Display name. Required on create. |
| `status` | Keeper status (`Active`, `Invited`, `Locked`, …). |
| `jobTitle` | Job title. |
| `twoFactorEnabled` | Read-only. |
| `aliases` | Read-only. |
| `node` | Entitlement, single-valued. Required on create. |
| `teams` | Entitlement, multi-valued (team UIDs). |
| `roles` | Entitlement, multi-valued (role IDs). |
| `folders` | Entitlement, multi-valued (see below). |
| `records` | Entitlement, multi-valued (see below). |

Only users with status `Active` are enabled in IdentityIQ. `Invited`, `Locked`
and other statuses are shown as disabled.

### Entitlement types (group schemas)

| Type | Identity | Display name |
|---|---|---|
| `node` | node ID | node path, e.g. `Org\Sales` |
| `team` | team UID | team name |
| `role` | role ID | role name |
| `folder` | `folderUid:CODE` for shared folders; `folderUid` for plain vault folders | `Name [Permission]` |
| `record` | `recordUid:CODE` | `Title [Permission]` |

Folder and record entitlements carry the permission level, so each level is a
separate entitlement that can be requested, approved and certified:

| Kind | Codes |
|---|---|
| Classic shared folder | `NP` No permissions, `MU` Manage users, `MR` Manage records, `MUR` Manage users & records |
| Nested shared folder (NSF) | `VW` Viewer, `SM` Share manager, `CM` Content manager, `CSM` Content share manager, `FM` Full manager |
| Classic record | `RO` Read-only, `CE` Can edit, `CS` Can share, `OW` Owner |
| NSF record | `VW`, `SM`, `CM`, `CSM`, `FM`, `OW` |

Only shares granted directly to a user are aggregated as that user's folder and
record entitlements. Team folder shares are listed on the team entitlement.

## Provisioning behavior

- **Create** invites the user (`enterprise-user --add`) with name, node, job
  title and any requested roles and teams. The user stays `Invited` (disabled)
  until they accept the Keeper invitation.
- **Update** applies node, role and team changes in one Commander call
  (removals before additions), then folder shares, record shares and finally
  an email change. Every folder and record change runs on its own; if some
  fail, the rest still run and each failure is reported on the request.
- **Folder permission change:** a user holds one permission level per shared
  folder. Requesting a new level replaces the current one.
- **Record rights:** removing a `CE` or `CS` record entitlement removes that
  right only and leaves the user read-only access. Remove the `RO`
  entitlement to remove the share completely.
- **Record ownership (`OW`)** cannot be granted or removed from IdentityIQ;
  do this in the Keeper vault.
- **Email change** uses Keeper's alias promotion: the new address becomes the
  primary email.
- **Disable / enable** lock and unlock the Keeper user.
- **Delete** runs `transfer-user`; Service Mode transfers the user's vault to
  the target user configured with `sailpoint-app-setup`, then removes the user.
  Deleting a user that no longer exists succeeds without changes.
- The connector refuses to delete, disable or update the Keeper account that
  Service Mode itself logs in with.
- Values containing line breaks are rejected; quotes and backslashes are
  escaped before a command is built.

## Configuration

| Setting | Required | Default | Description |
|---|---|---|---|
| Service Mode URL (`serviceUrl`) | Yes | | Base URL of Commander Service Mode, without `/api/v2`. |
| API Key (`apiKey`) | Yes | | Service Mode API key. Stored encrypted. |
| Poll Timeout (`pollTimeoutSeconds`) | No | 60 | How long to wait for one Commander command. |
| Sync Cache TTL (`syncCacheTtlSeconds`) | No | 30 | How long an enterprise or vault sync stays fresh before the next read syncs again. |

Enter them on the application's settings page: **Configuration → Settings**
on IdentityIQ 8.x, **Settings** on 9.0.

## Package contents

`build.sh` builds `dist/keeper-iiq-connector-*.zip` (with a `.sha256`
checksum file next to it). The zip holds only what IdentityIQ needs and the
install scripts; these docs are not included.

| Path | Purpose |
|---|---|
| `lib/keeper-iiq-connector-*.jar` | Connector |
| `web/define/applications/KeeperAttributesForm.xhtml` | Application settings page |
| `config/Keeper.xml` | Connector registry entry (application type "Keeper Security") |
| `config/Keeper-Application.xml` | Creates the "Keeper Security" application and its email correlation |
| `config/Keeper-Tasks.xml` | Aggregation tasks for the "Keeper Security" application |
| `config/Keeper-Entitlement-Refresh.xml` | Rule used by Entitlement Aggregation to apply names changed in Keeper |
| `install.sh` | Installs the connector, application type, application and tasks in one run (Linux, macOS, containers) |
| `uninstall.sh` | Removes the connector, its applications, tasks and application type |
| `config/Keeper-Uninstall.xml` | Rule used by `uninstall.sh` (deleted after it runs) |

## Installing

| IdentityIQ runs on | How to install |
|---|---|
| A Linux or macOS server | `./install.sh -d <identityiq>` on the server |
| A container (e.g. Docker) | Copy the zip into the container and run `install.sh` there |
| Windows, or no shell access | Copy the jar and settings page, then import the XML files from **Global Settings → Import from File** |

`<identityiq>` is the IdentityIQ web application directory, the one that
contains `WEB-INF`: usually `<tomcat>/webapps/identityiq` (e.g.
`/opt/tomcat/webapps/identityiq`, or `/usr/local/tomcat/webapps/identityiq`
in the official Tomcat container images). The same directory is passed to
`uninstall.sh`. See
[The IdentityIQ directory](INSTALL.md#the-identityiq-directory-identityiq)
in INSTALL.md to find it.

See [INSTALL.md](INSTALL.md) for the steps.
