package com.ezpnix.writeon.presentation.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezpnix.writeon.presentation.components.AppSnackbarKind
import com.ezpnix.writeon.presentation.components.LocalAppSnackbar
import com.ezpnix.writeon.presentation.navigation.BackGestureRouter
import com.ezpnix.writeon.presentation.screens.settings.model.Flashcard
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel
import androidx.compose.ui.res.stringResource
import com.ezpnix.writeon.R
import com.ezpnix.writeon.presentation.theme.appCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashcardScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val snackbar = LocalAppSnackbar.current
    val undoLabel = stringResource(R.string.cd_undo)
    val deletedMessage = stringResource(R.string.msg_flashcard_deleted)

    // PERF-14: the deck is Gson-parsed and unsealed through the Keystore, which used to happen
    // inside composition. Loading on IO keeps the first frame responsive. Until the stored deck is
    // in memory the content is a spinner and a save is skipped: an immediate back press would
    // otherwise persist the empty list the first frame shows over the user's real deck.
    val flashcards = remember { mutableStateListOf<Flashcard>() }
    var deckLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { settingsViewModel.loadFlashcards() }
        if (loaded.isNotEmpty()) flashcards.addAll(0, loaded)
        deckLoaded = true
    }
    var newWord by rememberSaveable { mutableStateOf("") }
    var newMeaning by rememberSaveable { mutableStateOf("") }
    var answerInput by rememberSaveable { mutableStateOf("") }
    var isTesting by rememberSaveable { mutableStateOf(false) }
    var currentCardIndex by rememberSaveable { mutableStateOf(0) }
    var testEnded by rememberSaveable { mutableStateOf(false) }
    var editingIndex by rememberSaveable { mutableStateOf(-1) }
    var feedbackMessage by rememberSaveable { mutableStateOf("") }
    var answerSubmitted by rememberSaveable { mutableStateOf(false) }
    var correctAnswers by rememberSaveable { mutableStateOf(0) }
    var wrongAnswers by rememberSaveable { mutableStateOf(0) }

    val exitScope = rememberCoroutineScope()
    // R2-05: back press + session eviction can both ask to leave; a second navigateUp would pop
    // the settings screen behind this one. One exit, one pop.
    var leaving by remember { mutableStateOf(false) }

    fun saveFlashcards() {
        if (!deckLoaded) return
        // A snapshot: the list is mutable Compose state, and the save serialises it on the caller's
        // thread while the user can still be editing.
        settingsViewModel.saveFlashcards(flashcards.toList())
    }

    val exitScreen: () -> Unit = exit@{
        if (leaving) return@exit
        leaving = true
        // PERF-14: await the off-main save before leaving, so a back press cannot outrun the deck
        // write and lose the last edit. A normal add/edit still uses the async fire-and-forget path.
        val snapshot = flashcards.toList()
        val loaded = deckLoaded
        exitScope.launch {
            // R5-06: an unexpected throw must still release the single-flight and keep the screen.
            // The ViewModel reports storage failures as `false`; this covers everything else.
            val saved = try {
                if (loaded) settingsViewModel.saveFlashcardsAndWait(snapshot) else true
            } catch (error: Exception) {
                android.util.Log.e("FlashcardScreen", "Could not save the deck before leaving", error)
                false
            }
            if (saved) {
                navController.navigateUp()
            } else {
                // R4-06/R5-06: a failed save keeps the screen (and the in-memory deck) instead of
                // navigating away from the only copy of the last edit. Back can retry.
                leaving = false
            }
        }
    }
    // The deck saves on the way out, so both the hardware button and the predictive back gesture
    // must run that save. Hardware back stays on BackHandler; the gesture is routed through
    // BackGestureRouter because PredictiveBackHost owns it (see EditScreen's registration).
    BackHandler(onBack = exitScreen)

    // R2-05/SEC-09: the deck lives in this composable's `mutableStateListOf`, not in the ViewModel,
    // so the ViewModel's clear never reached it. A Vault session ending (or an app lock engaging)
    // closes the screen through the normal exit path, which saves the deck first.
    val vaultActive by settingsViewModel.vaultSessionActive.collectAsStateWithLifecycle()
    val appLocked by settingsViewModel.appLocked.collectAsStateWithLifecycle()
    var sessionWasActive by remember { mutableStateOf(false) }
    LaunchedEffect(vaultActive, appLocked) {
        if (vaultActive && !appLocked) {
            sessionWasActive = true
            return@LaunchedEffect
        }
        if (!sessionWasActive && !appLocked) return@LaunchedEffect
        sessionWasActive = false
        // Do NOT clear the list before this: exitScreen snapshots it and writes the deck. The
        // composition (and the in-memory list) is discarded when the destination pops.
        exitScreen()
    }

    val previousGestureHandler = remember { BackGestureRouter.handler }
    val gestureHandlerSlot = remember { mutableStateOf<(() -> Unit)?>(null) }
    SideEffect {
        gestureHandlerSlot.value = exitScreen
        BackGestureRouter.handler = exitScreen
    }
    DisposableEffect(Unit) {
        onDispose {
            if (BackGestureRouter.handler === gestureHandlerSlot.value) {
                BackGestureRouter.handler = previousGestureHandler
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.flashcards)) },
                navigationIcon = {
                    IconButton(onClick = exitScreen) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        },
        content = { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                if (!deckLoaded) {
                    // The deck is read on IO; showing the empty add form first would look like the
                    // stored cards had vanished (and, during a test, momentarily show "complete").
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (!isTesting) {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = appCornerShape(),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.flashcard_add),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = newWord,
                                onValueChange = { newWord = it },
                                label = { Text(stringResource(R.string.flashcard_question)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = newMeaning,
                                onValueChange = { newMeaning = it },
                                label = { Text(stringResource(R.string.flashcard_answer)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                if (newWord.isNotBlank() && newMeaning.isNotBlank()) {
                                    flashcards.add(Flashcard(newWord, newMeaning))
                                    newWord = ""
                                    newMeaning = ""
                                    saveFlashcards()
                                }
                            }) {
                                Text(stringResource(R.string.action_add))
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        itemsIndexed(flashcards) { index, card ->
                            OutlinedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = appCornerShape(),
                                colors = CardDefaults.outlinedCardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    if (editingIndex == index) {
                                        OutlinedTextField(
                                            value = card.word,
                                            onValueChange = { flashcards[index] = card.copy(word = it) },
                                            label = { Text(stringResource(R.string.flashcard_question)) },
                                            singleLine = true
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedTextField(
                                            value = card.meaning,
                                            onValueChange = { flashcards[index] = card.copy(meaning = it) },
                                            label = { Text(stringResource(R.string.flashcard_answer)) },
                                            singleLine = true
                                        )
                                    } else {
                                        Text(
                                            stringResource(R.string.flashcard_list_item, index + 1, card.word, card.meaning),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.End,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        IconButton(onClick = {
                                            // UX-15: deleting used to be immediate and permanent. The
                                            // removed card is held by the snackbar's undo action and
                                            // put back at its old position, then saved again.
                                            val removed = flashcards.removeAt(index)
                                            saveFlashcards()
                                            snackbar?.show(
                                                message = deletedMessage,
                                                actionLabel = undoLabel,
                                                duration = SnackbarDuration.Long,
                                                kind = AppSnackbarKind.DELETE,
                                                onAction = {
                                                    flashcards.add(
                                                        index.coerceAtMost(flashcards.size),
                                                        removed
                                                    )
                                                    saveFlashcards()
                                                },
                                            )
                                        }) {
                                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                                        }
                                        IconButton(onClick = {
                                            editingIndex = if (editingIndex == index) -1 else index
                                            if (editingIndex == -1) saveFlashcards()
                                        }) {
                                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit))
                                        }
                                        if (editingIndex == index) {
                                            IconButton(onClick = {
                                                editingIndex = -1
                                                saveFlashcards()
                                            }) {
                                                Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.action_save))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(onClick = {
                        if (flashcards.isNotEmpty()) {
                            isTesting = true
                            testEnded = false
                            currentCardIndex = 0
                            answerInput = ""
                            feedbackMessage = ""
                            answerSubmitted = false
                            correctAnswers = 0
                            wrongAnswers = 0
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_start_test))
                    }
                } else if (!testEnded && currentCardIndex < flashcards.size) {
                    val currentCard = flashcards[currentCardIndex]

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            stringResource(R.string.flashcard_card_of, currentCardIndex + 1, flashcards.size),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(8.dp))

                        Text(stringResource(R.string.flashcard_word, currentCard.word), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = answerInput,
                            onValueChange = { answerInput = it },
                            label = { Text(stringResource(R.string.flashcard_type_meaning)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (!answerSubmitted && answerInput.isNotBlank()) {
                                    answerSubmitted = true
                                    if (answerInput.trim().equals(currentCard.meaning.trim(), ignoreCase = true)) {
                                        feedbackMessage = context.getString(R.string.flashcard_correct_exclaim)
                                        correctAnswers++
                                    } else {
                                        feedbackMessage = context.getString(R.string.flashcard_wrong_answer, currentCard.meaning)
                                        wrongAnswers++
                                    }
                                }
                            }),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !answerSubmitted
                        )

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                if (!answerSubmitted && answerInput.isNotBlank()) {
                                    answerSubmitted = true
                                    if (answerInput.trim().equals(currentCard.meaning.trim(), ignoreCase = true)) {
                                        feedbackMessage = context.getString(R.string.flashcard_correct_exclaim)
                                        correctAnswers++
                                    } else {
                                        feedbackMessage = context.getString(R.string.flashcard_wrong_answer, currentCard.meaning)
                                        wrongAnswers++
                                    }
                                }
                            },
                            enabled = !answerSubmitted
                        ) {
                            Text(stringResource(R.string.action_enter))
                        }

                        Spacer(Modifier.height(8.dp))

                        if (feedbackMessage.isNotEmpty()) {
                            Text(
                                feedbackMessage,
                                color = if (feedbackMessage.startsWith(stringResource(R.string.flashcard_correct)))
                                    MaterialTheme.colorScheme.tertiary
                                else
                                    MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                currentCardIndex++
                                feedbackMessage = ""
                                answerInput = ""
                                answerSubmitted = false
                            }) {
                                Text(stringResource(R.string.action_next))
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        OutlinedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            shape = appCornerShape(),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(R.string.flashcard_finished),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        testEnded = true
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.action_end_test))
                                }
                            }
                        }
                    }
                } else {
                    Text(stringResource(R.string.flashcard_test_complete), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    // U5: `tertiary` (#FFD54F) on the light surface measured 1.41:1 - effectively
                    // unreadable. The result summary takes the brand `primary` (#004D40, ~9.8:1);
                    // the wrong-answer line keeps `error`.
                    Text(stringResource(R.string.flashcard_correct_answers, correctAnswers), color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.flashcard_wrong_answers, wrongAnswers), color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        isTesting = false
                        testEnded = false
                        currentCardIndex = 0
                        answerInput = ""
                        feedbackMessage = ""
                        answerSubmitted = false
                        correctAnswers = 0
                        wrongAnswers = 0
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_restart))
                    }
                }
            }
        }
    )
}
