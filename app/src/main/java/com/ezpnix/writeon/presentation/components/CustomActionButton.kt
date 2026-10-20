package com.ezpnix.writeon.presentation.components

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CopyAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezpnix.writeon.presentation.screens.edit.model.EditViewModel
import com.ezpnix.writeon.presentation.screens.settings.widgets.copyToClipboard
import com.maxkeppeker.sheets.core.models.base.rememberUseCaseState
import com.maxkeppeler.sheets.calendar.CalendarDialog
import com.maxkeppeler.sheets.calendar.models.CalendarConfig
import com.maxkeppeler.sheets.calendar.models.CalendarSelection
import com.maxkeppeler.sheets.calendar.models.CalendarStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.Stack
import androidx.compose.ui.res.stringResource
import com.ezpnix.writeon.R
import com.ezpnix.writeon.presentation.theme.appCornerShape


@Composable
fun AgreeButton(
    text: String,
    onClick: () -> Unit
) {
    ExtendedFloatingActionButton(
        modifier = Modifier.imePadding(),
        shape = appCornerShape(),
        onClick = { onClick() },
        icon = { Icon(Icons.Rounded.CheckCircle, text) },
        text = { Text(text = text) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditButton(pagerState: PagerState, coroutineScope: CoroutineScope) {
    IconButton(
        modifier = Modifier.size(56.dp),
        onClick = {
            coroutineScope.launch {
                pagerState.animateScrollToPage(0)
            }
        }
    ) {
        Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.edit), tint = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PreviewButton(
    pagerState: PagerState,
    coroutineScope: CoroutineScope,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    IconButton(
        modifier = modifier.size(56.dp),
        onClick = {
            onClick()
            coroutineScope.launch {
                pagerState.animateScrollToPage(1)
            }
        }
    ) {
        Icon(Icons.Rounded.RemoveRedEye, contentDescription = stringResource(R.string.cd_preview), tint = MaterialTheme.colorScheme.primary)
    }
}


@Composable
fun TxtButton(currentText: String) {
    val context = LocalContext.current

    val clipboardManager = LocalClipboardManager.current

    val saveToFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(currentText.toByteArray())
                    }
                    Toast.makeText(context, context.getString(R.string.msg_text_saved), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.msg_error_saving_file, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }
    )

    IconButton(
        modifier = Modifier.size(56.dp),
        onClick = {
            clipboardManager.setText(AnnotatedString(currentText))
            Toast.makeText(context, context.getString(R.string.msg_exporting), Toast.LENGTH_SHORT).show()

            saveToFileLauncher.launch("rename.txt")
        }
    ) {
        Icon(Icons.Rounded.AddComment, contentDescription = context.getString(R.string.cd_copy_and_save))
    }
}

@Composable
fun BrowserButton() {
    val context = LocalContext.current

    IconButton(
        modifier = Modifier.size(56.dp),
        onClick = {
            val url = "https://www.startpage.com"

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

            context.startActivity(intent)
            Toast.makeText(context, context.getString(R.string.msg_opening_browser), Toast.LENGTH_SHORT).show()
        }
    )
    {
        Icon(Icons.Rounded.Search, null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalButton() {
    val selectedDates = remember { mutableStateOf<List<LocalDate>>(listOf()) }
    val disabledDates = listOf(
        LocalDate.now().minusDays(0),
    )
    val calendarState = rememberUseCaseState()
    val context = LocalContext.current
    val currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))
    val dayOfWeek = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())

    IconButton(
        modifier = Modifier.size(56.dp),
        onClick = {
            calendarState.show()
        }

    ) {
        Icon(Icons.Rounded.CalendarMonth, contentDescription = stringResource(R.string.quick_action_calendar))
    }

    CalendarDialog(
        state = calendarState,
        config = CalendarConfig(
            yearSelection = true,
            monthSelection = true,
            style = CalendarStyle.MONTH,
            disabledDates = disabledDates
        ),
        selection = CalendarSelection.Dates { newDates ->
            selectedDates.value = newDates
        }
    )
}

@Composable
fun TranslateButton(viewModel: EditViewModel, onClick: () -> Unit) {
    val context = LocalContext.current

    IconButton(
        modifier = Modifier.size(56.dp),
        onClick = {
            copyToClipboard(context, viewModel.noteDescription.value.text)
            openTranslateApp(context, viewModel.noteDescription.value.text)
        }
    ) {
        Icon(Icons.Rounded.Translate, null)
    }
}

@Composable
fun CopyButton(viewModel: EditViewModel, onClick: () -> Unit) {
    val context = LocalContext.current

    IconButton(
        modifier = Modifier.size(56.dp),
        onClick = {
            copyToClipboard(context, viewModel.noteDescription.value.text)
        }
    ) {
        Icon(Icons.Rounded.CopyAll, null)
    }
}

@SuppressLint("ServiceCast")
fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = android.content.ClipData.newPlainText(context.getString(R.string.clipboard_label_copied_text), text)
    clipboard.setPrimaryClip(clip)
}

fun openTranslateApp(context: Context, text: String) {
    val uri = Uri.parse("https://translate.google.com/?sl=auto&tl=en&text=${Uri.encode(text)}")
    val intent = Intent(Intent.ACTION_VIEW, uri)
    context.startActivity(intent)
}

@Composable
fun CalculatorButton() {
    val showDialog = remember { mutableStateOf(false) }

    IconButton(
        modifier = Modifier.size(56.dp),
        onClick = { showDialog.value = true }
    ) {
        Icon(Icons.Rounded.Calculate, contentDescription = null)
    }

    if (showDialog.value) {
        AlertDialog(
            onDismissRequest = { showDialog.value = false },
            title = { Text(stringResource(R.string.quick_action_calculator)) },
            text = {
                CalculatorUI()
            },
            confirmButton = {
                TextButton(onClick = { showDialog.value = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }
}

@Composable
fun CalculatorUI() {
    val context = LocalContext.current
    var expression by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("0") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TextField(
            value = expression,
            onValueChange = { input ->
                val sanitized = input.filter { it.isDigit() || it in "+-*/().%" }
                expression = sanitized
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        val buttons = listOf(
            listOf("C", "()", "%", "/"),
            listOf("7", "8", "9", "*"),
            listOf("4", "5", "6", "-"),
            listOf("1", "2", "3", "+"),
            listOf("0", ".", "<", "="),
        )

        buttons.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { symbol ->
                    Button(
                        onClick = {
                            when (symbol) {
                                "C" -> {
                                    expression = ""
                                    result = "0"
                                }
                                "=" -> {
                                    result = try {
                                        evalExpression(expression).toString()
                                    } catch (e: Exception) {
                                        context.getString(R.string.error)
                                    }
                                }
                                "%" -> {
                                    if (expression.isNotEmpty()) {
                                        expression = (evalExpression(expression) / 100).toString()
                                        result = expression
                                    }
                                }
                                "( )" -> {
                                    val openCount = expression.count { it == '(' }
                                    val closeCount = expression.count { it == ')' }

                                    expression += if (openCount > closeCount) ")" else "("
                                }
                                "." -> {
                                    if (!expression.endsWith(".")) {
                                        expression += symbol
                                    }
                                }
                                "<" -> {
                                    if (expression.isNotEmpty()) {
                                        expression = expression.dropLast(1)
                                    }
                                    result = "0"
                                }
                                else -> expression += symbol
                            }
                        },
                        modifier = Modifier.size(64.dp)
                    ) {
                        Text(
                            symbol,
                            style = TextStyle(fontSize = 18.sp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = appCornerShape()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.calculator_result),
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                )
                Text(
                    text = result,
                    style = TextStyle(
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

    }
}

fun evalExpression(expression: String): Double {
    return try {
        val outputQueue = mutableListOf<String>()
        val operatorStack = Stack<Char>()
        val tokens = Regex("""\d+(\.\d+)?|[()+\-*/]""").findAll(expression.replace(" ", "")).map { it.value }.toList()

        val precedence = mapOf('+' to 1, '-' to 1, '*' to 2, '/' to 2)

        for (token in tokens) {
            when {
                token.toDoubleOrNull() != null -> outputQueue.add(token)
                token.singleOrNull() in precedence -> {
                    while (operatorStack.isNotEmpty() &&
                        operatorStack.peek() != '(' &&
                        precedence[operatorStack.peek()]!! >= precedence[token[0]]!!
                    ) {
                        outputQueue.add(operatorStack.pop().toString())
                    }
                    operatorStack.push(token[0])
                }
                token == "(" -> operatorStack.push('(')
                token == ")" -> {
                    while (operatorStack.isNotEmpty() && operatorStack.peek() != '(') {
                        outputQueue.add(operatorStack.pop().toString())
                    }
                    if (operatorStack.isNotEmpty() && operatorStack.peek() == '(') {
                        operatorStack.pop()
                    }
                }
            }
        }

        while (operatorStack.isNotEmpty()) {
            outputQueue.add(operatorStack.pop().toString())
        }

        val evalStack = Stack<Double>()
        for (token in outputQueue) {
            when {
                token.toDoubleOrNull() != null -> evalStack.push(token.toDouble())
                token.length == 1 && token[0] in precedence -> {
                    val b = evalStack.pop()
                    val a = evalStack.pop()
                    val result = when (token[0]) {
                        '+' -> a + b
                        '-' -> a - b
                        '*' -> a * b
                        '/' -> if (b != 0.0) a / b else return Double.NaN
                        else -> return Double.NaN
                    }
                    evalStack.push(result)
                }
            }
        }

        return evalStack.pop()
    } catch (e: Exception) {
        Double.NaN
    }
}
