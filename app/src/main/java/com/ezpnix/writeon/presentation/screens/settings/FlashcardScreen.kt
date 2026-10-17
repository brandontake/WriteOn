package com.ezpnix.writeon.presentation.screens.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ezpnix.writeon.presentation.screens.settings.model.Flashcard
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel
import androidx.compose.ui.res.stringResource
import com.ezpnix.writeon.R
import com.ezpnix.writeon.presentation.theme.appCornerShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashcardScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel
) {
    val context = LocalContext.current

    val flashcards = remember { mutableStateListOf<Flashcard>().apply { addAll(settingsViewModel.loadFlashcards()) } }
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

    fun saveFlashcards() {
        settingsViewModel.saveFlashcards(flashcards)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.flashcards)) },
                navigationIcon = {
                    IconButton(onClick = {
                        saveFlashcards()
                        navController.navigateUp()
                    }) {
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
                if (!isTesting) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = appCornerShape(),
                        elevation = CardDefaults.cardElevation(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.flashcard_add), style = MaterialTheme.typography.titleMedium)
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
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = appCornerShape(),
                                elevation = CardDefaults.cardElevation(2.dp)
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
                                        Text(stringResource(R.string.flashcard_list_item, index + 1, card.word, card.meaning))
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.End,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        IconButton(onClick = {
                                            flashcards.removeAt(index)
                                            saveFlashcards()
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

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            shape = appCornerShape(),
                            elevation = CardDefaults.cardElevation(4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.flashcard_finished), style = MaterialTheme.typography.titleMedium)
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
                    Text(stringResource(R.string.flashcard_correct_answers, correctAnswers), color = MaterialTheme.colorScheme.tertiary)
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
