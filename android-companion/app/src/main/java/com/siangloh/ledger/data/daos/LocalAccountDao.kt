package com.siangloh.ledger.data.daos

import androidx.room.*
import com.siangloh.ledger.data.entities.LocalAccount

@Dao
interface LocalAccountDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: LocalAccount): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(accounts: List<LocalAccount>)

    @Update
    suspend fun update(account: LocalAccount)

    @Query("DELETE FROM local_accounts WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT * FROM local_accounts WHERE id = :id")
    suspend fun getById(id: Int): LocalAccount?

    @Query("SELECT * FROM local_accounts ORDER BY orderNum ASC, id ASC")
    suspend fun getAll(): List<LocalAccount>

    @Query("SELECT COUNT(*) FROM local_accounts")
    suspend fun countAll(): Int

    @Query("UPDATE local_accounts SET balance = balance + :delta WHERE id = :id")
    suspend fun updateBalance(id: Int, delta: Double)
}
