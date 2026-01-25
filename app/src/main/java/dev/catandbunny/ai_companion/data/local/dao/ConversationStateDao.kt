package dev.catandbunny.ai_companion.data.local.dao

import androidx.room.*
import dev.catandbunny.ai_companion.data.local.entity.ConversationStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationStateDao {
    @Query("SELECT * FROM conversation_state WHERE id = 1")
    fun getConversationState(): Flow<ConversationStateEntity?>

    @Query("SELECT * FROM conversation_state WHERE id = 1")
    suspend fun getConversationStateSync(): ConversationStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversationState(state: ConversationStateEntity)

    @Update
    suspend fun updateConversationState(state: ConversationStateEntity)

    @Query("DELETE FROM conversation_state")
    suspend fun deleteConversationState()
}
