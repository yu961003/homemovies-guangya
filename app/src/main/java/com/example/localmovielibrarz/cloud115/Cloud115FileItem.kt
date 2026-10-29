package com.example.localmovielibrarz.cloud115

data class Cloud115FileItem(
    val name: String,
    val cid: Long?,
    val fid: Long?,
    val pickcode: String?,
    val size: Long?,
    val addedAt: Long?,
    val modifiedAt: Long?,
    val isDirectory: Boolean
)
