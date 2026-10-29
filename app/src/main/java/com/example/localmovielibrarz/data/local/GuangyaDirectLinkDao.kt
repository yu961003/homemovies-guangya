package com.example.localmovielibrarz.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GuangyaDirectLinkDao {
    @Query("SELECT * FROM guangya_direct_links WHERE fileId = :fileId LIMIT 1")
    suspend fun get(fileId: String): GuangyaDirectLinkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GuangyaDirectLinkEntity)

    @Query("DELETE FROM guangya_direct_links WHERE fileId = :fileId")
    suspend fun delete(fileId: String)
}
