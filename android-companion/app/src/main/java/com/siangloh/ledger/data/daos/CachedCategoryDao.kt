package com.siangloh.ledger.data.daos

import androidx.room.*
import com.siangloh.ledger.data.entities.CachedCategory

@Dao
interface CachedCategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CachedCategory>)

    @Query("SELECT * FROM cached_categories ORDER BY type, id ASC")
    suspend fun getAllCategories(): List<CachedCategory>

    @Query("SELECT * FROM cached_categories WHERE type = :type ORDER BY id ASC")
    suspend fun getCategoriesByType(type: String): List<CachedCategory>

    @Query("DELETE FROM cached_categories")
    suspend fun clearAll()
}
