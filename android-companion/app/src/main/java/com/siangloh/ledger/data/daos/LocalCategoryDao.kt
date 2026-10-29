package com.siangloh.ledger.data.daos

import androidx.room.*
import com.siangloh.ledger.data.entities.LocalCategory

@Dao
interface LocalCategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: LocalCategory): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<LocalCategory>)

    @Query("SELECT * FROM local_categories ORDER BY orderNum ASC, id ASC")
    suspend fun getAll(): List<LocalCategory>

    @Query("SELECT * FROM local_categories WHERE type = :type ORDER BY orderNum ASC, id ASC")
    suspend fun getByType(type: String): List<LocalCategory>

    @Query("SELECT COUNT(*) FROM local_categories")
    suspend fun countAll(): Int

    @Query("DELETE FROM local_categories WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM local_categories")
    suspend fun deleteAll()
}
