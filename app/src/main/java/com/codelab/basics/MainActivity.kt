package com.codelab.basics

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// --- Data Layer ---
@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val amount: Double,
    val category: String,
    val date: Date,
    val description: String,
    val type: TransactionType
)

enum class TransactionType {
    INCOME,
    EXPENSE
}

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val type: TransactionType
)

@Dao
interface TransactionDao {
    @Insert
    suspend fun insertTransaction(transaction: Transaction)

    @Query("SELECT * FROM transactions ORDER BY date DESC")
    fun getAllTransactions(): Flow<List<Transaction>>

    @Insert
    suspend fun insertCategory(category: Category)

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY name ASC")
    fun getCategories(type: TransactionType): Flow<List<Category>>
}

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }
}

@Database(entities = [Transaction::class, Category::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "accounting_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// --- ViewModel Layer ---
data class TransactionSummary(
    val totalIncome: Double,
    val totalExpense: Double,
    val balance: Double,
    val expenseByCategory: Map<String, Double>
)

class TransactionViewModel(private val db: AppDatabase) : ViewModel() {
    val allTransactions: Flow<List<Transaction>> = db.transactionDao().getAllTransactions()
    val incomeCategories: Flow<List<Category>> = db.transactionDao().getCategories(TransactionType.INCOME)
    val expenseCategories: Flow<List<Category>> = db.transactionDao().getCategories(TransactionType.EXPENSE)

    val summary: Flow<TransactionSummary> = allTransactions.map { transactions ->
        val totalIncome = transactions.filter { it.type == TransactionType.INCOME }.sumOf { it.amount }
        val totalExpense = transactions.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
        val balance = totalIncome - totalExpense
        val expenseByCategory = transactions
            .filter { it.type == TransactionType.EXPENSE }
            .groupBy { it.category }
            .mapValues { entry -> entry.value.sumOf { it.amount } }

        TransactionSummary(totalIncome, totalExpense, balance, expenseByCategory)
    }

    fun insertTransaction(transaction: Transaction) {
        viewModelScope.launch {
            db.transactionDao().insertTransaction(transaction)
        }
    }

    fun insertCategory(category: Category) {
        viewModelScope.launch {
            db.transactionDao().insertCategory(category)
        }
    }
}

class TransactionViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TransactionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TransactionViewModel(db) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// --- UI Layer ---
class MainActivity : ComponentActivity() {

    private val database by lazy { AppDatabase.getDatabase(this) }
    private val viewModel: TransactionViewModel by viewModels {
        TransactionViewModelFactory(database)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            if (viewModel.expenseCategories.first().isEmpty()) {
                viewModel.insertCategory(Category(name = "Default", type = TransactionType.EXPENSE))
            }
            if (viewModel.incomeCategories.first().isEmpty()) {
                viewModel.insertCategory(Category(name = "Salary", type = TransactionType.INCOME))
            }
        }
        setContent {
            AccountingApp(viewModel)
        }
    }
}

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Transactions : Screen("transactions", "Transactions", Icons.Default.Home)
    object Statistics : Screen("statistics", "Statistics", Icons.Default.Star)
}

val navItems = listOf(
    Screen.Transactions,
    Screen.Statistics
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountingApp(viewModel: TransactionViewModel) {
    val navController = rememberNavController()
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        AddTransactionDialog(
            viewModel = viewModel,
            onDismiss = { showDialog = false }
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                navItems.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = null) },
                        label = { Text(screen.label) },
                        selected = false, // Simplified for now
                        onClick = { navController.navigate(screen.route) }
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Transaction")
            }
        }
    ) { innerPadding ->
        NavHost(navController, startDestination = Screen.Transactions.route, Modifier.padding(innerPadding)) {
            composable(Screen.Transactions.route) { TransactionsScreen(viewModel) }
            composable(Screen.Statistics.route) { StatisticsScreen(viewModel) }
        }
    }
}

@Composable
fun TransactionsScreen(viewModel: TransactionViewModel) {
    val transactions by viewModel.allTransactions.collectAsState(initial = emptyList())

    LazyColumn {
        items(transactions) { transaction ->
            TransactionItem(transaction)
        }
    }
}

@Composable
fun TransactionItem(transaction: Transaction) {
    val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "${transaction.type}: ${transaction.amount}", style = MaterialTheme.typography.titleMedium)
            Text(text = "Category: ${transaction.category}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "Date: ${dateFormatter.format(transaction.date)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun StatisticsScreen(viewModel: TransactionViewModel) {
    val summary by viewModel.summary.collectAsState(
        initial = TransactionSummary(0.0, 0.0, 0.0, emptyMap())
    )

    Column(modifier = Modifier.padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SummaryCard("Income", "%.2f".format(summary.totalIncome))
            SummaryCard("Expense", "%.2f".format(summary.totalExpense))
            SummaryCard("Balance", "%.2f".format(summary.balance))
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (summary.expenseByCategory.isNotEmpty()) {
            PieChart(data = summary.expenseByCategory)
            Spacer(modifier = Modifier.height(24.dp))
            StatisticsTable(data = summary.expenseByCategory)
        } else {
            Text("No expense data available.", modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
fun SummaryCard(title: String, value: String) {
    Card(modifier = Modifier.padding(4.dp)) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = title, style = MaterialTheme.typography.labelMedium)
            Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun PieChart(data: Map<String, Double>) {
    val total = data.values.sum()
    val angles = data.map { (it.value / total * 360).toFloat() }
    val colors = listOf(
        Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF673AB7),
        Color(0xFF3F51B5), Color(0xFF2196F3), Color(0xFF03A9F4),
        Color(0xFF00BCD4), Color(0xFF009688), Color(0xFF4CAF50),
        Color(0xFF8BC34A), Color(0xFFCDDC39), Color(0xFFFFEB3B)
    )

    Canvas(modifier = Modifier.fillMaxWidth().height(200.dp)) {
        var startAngle = -90f
        angles.forEachIndexed { index, angle ->
            drawArc(
                color = colors[index % colors.size],
                startAngle = startAngle,
                sweepAngle = angle,
                useCenter = true
            )
            startAngle += angle
        }
    }
}

@Composable
fun StatisticsTable(data: Map<String, Double>) {
    val total = data.values.sum()
    LazyColumn {
        item {
            Row(modifier = Modifier.padding(vertical = 8.dp)) {
                Text("Category", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("Amount", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("Percentage", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
            }
        }
        items(data.toList()) { (category, amount) ->
            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(category, modifier = Modifier.weight(1f))
                Text("%.2f".format(amount), modifier = Modifier.weight(1f))
                Text("%.1f%%".format(amount / total * 100), modifier = Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionDialog(viewModel: TransactionViewModel, onDismiss: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var transactionType by remember { mutableStateOf(TransactionType.EXPENSE) }
    val tabTitles = listOf("Expense", "Income")

    var selectedDate by remember { mutableStateOf(Date()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    val expenseCategories by viewModel.expenseCategories.collectAsState(initial = emptyList())
    val incomeCategories by viewModel.incomeCategories.collectAsState(initial = emptyList())
    val categories = if (transactionType == TransactionType.EXPENSE) expenseCategories else incomeCategories

    var expanded by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("") }

    LaunchedEffect(transactionType, categories) {
        selectedCategory = categories.firstOrNull()?.name ?: ""
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = selectedDate.time)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    selectedDate = Date(datePickerState.selectedDateMillis!!)
                    showDatePicker = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Transaction") },
        text = {
            Column {
                TabRow(selectedTabIndex = transactionType.ordinal) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = transactionType.ordinal == index,
                            onClick = { transactionType = if (index == 0) TransactionType.EXPENSE else TransactionType.INCOME },
                            text = { Text(title) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount") }
                )
                Spacer(modifier = Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selectedCategory,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.name) },
                                onClick = {
                                    selectedCategory = category.name
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") }
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Date: ${dateFormatter.format(selectedDate)}", modifier = Modifier.weight(1f))
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Default.DateRange, contentDescription = "Select Date")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val transaction = Transaction(
                        amount = amount.toDoubleOrNull() ?: 0.0,
                        category = selectedCategory,
                        date = selectedDate,
                        description = description,
                        type = transactionType
                    )
                    viewModel.insertTransaction(transaction)
                    onDismiss()
                },
                enabled = selectedCategory.isNotEmpty() && amount.isNotEmpty()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}