package com.siangloh.ledger.data.daos

import androidx.room.*
import com.siangloh.ledger.data.entities.LocalTransaction

@Dao
interface LocalTransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: LocalTransaction): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<LocalTransaction>)

    @Update
    suspend fun update(transaction: LocalTransaction)

    @Query("DELETE FROM local_transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM local_transactions WHERE id = :id")
    suspend fun getById(id: Long): LocalTransaction?

    @Query("SELECT * FROM local_transactions ORDER BY date DESC, id DESC")
    suspend fun getAll(): List<LocalTransaction>

    @Query("SELECT * FROM local_transactions WHERE date LIKE :monthPrefix ORDER BY date DESC, id DESC")
    suspend fun getByMonth(monthPrefix: String): List<LocalTransaction>

    @Query("SELECT * FROM local_transactions WHERE date >= :startDate AND date <= :endDate ORDER BY date DESC, id DESC")
    suspend fun getByDateRange(startDate: String, endDate: String): List<LocalTransaction>

    @Query("SELECT * FROM local_transactions WHERE date LIKE :yearPrefix ORDER BY date DESC, id DESC")
    suspend fun getByYear(yearPrefix: String): List<LocalTransaction>

    @Query("UPDATE local_transactions SET category = :category, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateCategory(id: Long, category: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM local_transactions WHERE accountId = :accountId OR toAccountId = :accountId ORDER BY date DESC, id DESC")
    suspend fun getByAccountId(accountId: Int): List<LocalTransaction>

    @Query("SELECT COUNT(*) FROM local_transactions")
    suspend fun countAll(): Int

    @Query("DELETE FROM local_transactions")
    suspend fun deleteAll()
}
