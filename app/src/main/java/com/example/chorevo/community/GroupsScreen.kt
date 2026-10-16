package com.example.chorevo.community

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chorevo.community.ViewModel.GroupsViewModel
import com.example.chorevo.core.AnimatedEmptyState
import com.example.chorevo.core.ShimmerList
import com.example.chorevo.firebase.UserProfile
import com.example.chorevo.ui.theme.ChorevoTheme
import com.example.chorevo.ui.theme.PillShape

/**
 * Groups tab: the user's groups and public groups to discover, with create
 * and join-by-code. Opening a group shows [GroupDetailScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    profile: UserProfile,
    vm: GroupsViewModel,
    onOpenDance: (String) -> Unit,
    pendingInviteCode: String? = null,
    onInviteHandled: () -> Unit = {}
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val displayName = profile.displayName?.trim().takeUnless { it.isNullOrBlank() } ?: "User"

    LaunchedEffect(profile.uid) { vm.bind(profile.uid, displayName, profile.photoUrl) }
    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.clearMessage()
    }

    var showCreate by rememberSaveable { mutableStateOf(false) }
    var showJoinCode by rememberSaveable { mutableStateOf(false) }
    var joinCodePrefill by rememberSaveable { mutableStateOf("") }
    val scanInvite: () -> Unit = {
        InviteQr.scan(
            context,
            onResult = { code ->
                if (code != null) vm.joinWithCode(code) else vm.showMessage("That QR code isn't a Chorevo group invite.")
            },
            onError = { e -> vm.showMessage("Couldn't open the scanner: ${e.message}") }
        )
    }

    // An invite link or a QR code scanned with the phone camera: confirm, then join.
    LaunchedEffect(pendingInviteCode) {
        val code = pendingInviteCode ?: return@LaunchedEffect
        if (state.openGroupId != null) vm.closeGroup()
        joinCodePrefill = code
        showJoinCode = true
        onInviteHandled()
    }

    if (state.openGroupId != null) {
        GroupDetailScreen(
            state = state,
            myUid = profile.uid,
            vm = vm,
            snackbar = snackbar,
            onOpenDance = onOpenDance
        )
        return
    }

    val accent = ChorevoTheme.colors.accent

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Groups", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = scanInvite, modifier = Modifier.testTag("scanInviteButton")) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = "Scan a group QR code")
                    }
                    IconButton(
                        onClick = { joinCodePrefill = ""; showJoinCode = true },
                        modifier = Modifier.testTag("joinCodeButton")
                    ) {
                        Icon(Icons.Outlined.VpnKey, contentDescription = "Join with invite code")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New group") },
                containerColor = accent,
                contentColor = ChorevoTheme.colors.onAccent,
                modifier = Modifier.testTag("createGroupButton")
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = state.tab.ordinal,
                containerColor = Color.Transparent,
                contentColor = accent,
                indicator = { positions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(positions[state.tab.ordinal]),
                        color = accent
                    )
                }
            ) {
                Tab(
                    selected = state.tab == GroupsTab.MY_GROUPS,
                    onClick = { vm.selectTab(GroupsTab.MY_GROUPS) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("My groups")
                            if (state.invitations.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                Badge { Text(state.invitations.size.toString()) }
                            }
                        }
                    },
                    modifier = Modifier.testTag("groupsTab_my")
                )
                Tab(
                    selected = state.tab == GroupsTab.DISCOVER,
                    onClick = { vm.selectTab(GroupsTab.DISCOVER) },
                    text = { Text("Discover") },
                    modifier = Modifier.testTag("groupsTab_discover")
                )
            }

            when (state.tab) {
                GroupsTab.MY_GROUPS -> MyGroupsList(
                    state = state,
                    onOpen = vm::openGroup,
                    onAccept = vm::acceptInvitation,
                    onDecline = vm::declineInvitation,
                    onCreate = { showCreate = true },
                    onDiscover = { vm.selectTab(GroupsTab.DISCOVER) }
                )
                GroupsTab.DISCOVER -> DiscoverList(
                    state = state,
                    onQueryChange = vm::setDiscoverQuery,
                    onOpen = vm::openGroup
                )
            }
        }
    }

    if (showCreate) {
        CreateGroupDialog(
            working = state.isWorking,
            onDismiss = { showCreate = false },
            onCreate = { name, description, private ->
                vm.createGroup(name, description, private)
                showCreate = false
            }
        )
    }
    if (showJoinCode) {
        JoinCodeDialog(
            initialCode = joinCodePrefill,
            working = state.isWorking,
            onScan = { showJoinCode = false; scanInvite() },
            onDismiss = { showJoinCode = false },
            onJoin = { code ->
                vm.joinWithCode(code)
                showJoinCode = false
            }
        )
    }
}

@Composable
private fun MyGroupsList(
    state: GroupsUiState,
    onOpen: (String) -> Unit,
    onAccept: (GroupInvitation) -> Unit,
    onDecline: (GroupInvitation) -> Unit,
    onCreate: () -> Unit,
    onDiscover: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        if (state.invitations.isNotEmpty()) {
            InvitationsSection(
                invitations = state.invitations,
                answeringId = state.answeringInvitationId,
                onAccept = onAccept,
                onDecline = onDecline
            )
        }
        Box(Modifier.weight(1f)) {
            MyGroupsContent(state, onOpen, onCreate, onDiscover)
        }
    }
}

/** Invitations sent to the user by username, with Accept / Decline. */
@Composable
private fun InvitationsSection(
    invitations: List<GroupInvitation>,
    answeringId: String?,
    onAccept: (GroupInvitation) -> Unit,
    onDecline: (GroupInvitation) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .testTag("invitationsSection"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (invitations.size == 1) "1 invitation" else "${invitations.size} invitations",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        invitations.take(5).forEach { inv ->
            val busy = answeringId == inv.id
            Surface(
                shape = MaterialTheme.shapes.large,
                color = ChorevoTheme.colors.accent.copy(alpha = 0.12f),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("invitation_${inv.groupId}")
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    GroupAvatar(inv.groupName, size = 40)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            inv.groupName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${inv.inviterName} invited you",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = { onDecline(inv) },
                        enabled = answeringId == null,
                        modifier = Modifier.testTag("declineInvitation_${inv.groupId}")
                    ) { Text("Decline") }
                    Button(
                        onClick = { onAccept(inv) },
                        enabled = answeringId == null,
                        shape = PillShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ChorevoTheme.colors.accent,
                            contentColor = ChorevoTheme.colors.onAccent
                        ),
                        modifier = Modifier.testTag("acceptInvitation_${inv.groupId}")
                    ) { Text(if (busy) "…" else "Join") }
                }
            }
        }
    }
}

@Composable
private fun MyGroupsContent(
    state: GroupsUiState,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onDiscover: () -> Unit
) {
    when {
        state.isLoadingMyGroups -> ShimmerList(rows = 4, modifier = Modifier.padding(16.dp))
        state.myGroups.isEmpty() -> Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedEmptyState(
                icon = Icons.Filled.Groups,
                title = "You're not in any groups yet",
                message = "Create one for your crew, or find a public group to join."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onCreate,
                    shape = PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ChorevoTheme.colors.accent,
                        contentColor = ChorevoTheme.colors.onAccent
                    )
                ) { Text("Create a group") }
                TextButton(onClick = onDiscover) { Text("Discover groups") }
            }
        }
        else -> GroupList(groups = state.myGroups, joinedIds = state.myGroupIds, onOpen = onOpen)
    }
}

@Composable
private fun DiscoverList(
    state: GroupsUiState,
    onQueryChange: (String) -> Unit,
    onOpen: (String) -> Unit
) {
    Column {
        OutlinedTextField(
            value = state.discoverQuery,
            onValueChange = onQueryChange,
            placeholder = { Text("Search public groups") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = PillShape,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("discoverSearch")
        )
        when {
            state.isLoadingDiscover && state.discoverGroups.isEmpty() ->
                ShimmerList(rows = 4, modifier = Modifier.padding(16.dp))
            state.discoverGroups.isEmpty() -> AnimatedEmptyState(
                icon = Icons.Filled.Public,
                title = if (state.discoverQuery.isBlank()) "No public groups yet" else "No groups match",
                message = "Start one and invite your dance friends."
            )
            else -> GroupList(groups = state.discoverGroups, joinedIds = state.myGroupIds, onOpen = onOpen)
        }
    }
}

@Composable
private fun GroupList(groups: List<DanceGroup>, joinedIds: Set<String>, onOpen: (String) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(groups, key = { it.id }) { group ->
            GroupCard(group = group, joined = group.id in joinedIds, onClick = { onOpen(group.id) })
        }
    }
}

@Composable
internal fun GroupAvatar(name: String, size: Int = 48) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(ChorevoTheme.colors.brandGradient),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.trim().take(1).uppercase().ifBlank { "#" },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = ChorevoTheme.colors.onGradient
        )
    }
}

@Composable
private fun GroupCard(group: DanceGroup, joined: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("groupCard_${group.id}")
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GroupAvatar(group.name)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (group.isPrivate) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = "Private",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (group.description.isNotBlank()) {
                    Text(
                        group.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    memberCountLabel(group.memberCount) + if (joined) " · Joined" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (joined) ChorevoTheme.colors.accent else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal fun memberCountLabel(count: Int?): String {
    val n = count ?: 1
    return if (n == 1) "1 member" else "$n members"
}

@Composable
private fun CreateGroupDialog(
    working: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, private: Boolean) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var private by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text("Name") },
                    singleLine = true,
                    supportingText = { Text("3–60 characters") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("groupNameField")
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(500) },
                    label = { Text("What's it about? (optional)") },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("groupDescriptionField")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !private,
                        onClick = { private = false },
                        label = { Text("Public") },
                        leadingIcon = { Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = private,
                        onClick = { private = true },
                        label = { Text("Private") },
                        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.testTag("groupPrivateChip")
                    )
                }
                Text(
                    if (private) "Hidden from Discover. People join with an invite code you share."
                    else "Anyone can find and join it from Discover.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, description, private) },
                enabled = !working && isValidGroupName(name),
                modifier = Modifier.testTag("confirmCreateGroup")
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun JoinCodeDialog(
    initialCode: String,
    working: Boolean,
    onScan: () -> Unit,
    onDismiss: () -> Unit,
    onJoin: (String) -> Unit
) {
    var code by rememberSaveable(initialCode) { mutableStateOf(normalizeInviteCode(initialCode)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join with invite code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Scan the group's QR code, or enter the 8-character code a member shared.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = normalizeInviteCode(it) },
                    placeholder = { Text("e.g. K7QW2MZP") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    textStyle = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("inviteCodeField")
                )
                TextButton(onClick = onScan, modifier = Modifier.testTag("scanInsteadButton")) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan QR code instead")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onJoin(code) },
                enabled = !working && code.length == INVITE_CODE_LENGTH,
                modifier = Modifier.testTag("confirmJoinCode")
            ) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

