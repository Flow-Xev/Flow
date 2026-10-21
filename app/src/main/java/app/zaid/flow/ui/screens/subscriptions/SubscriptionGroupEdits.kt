package app.zaid.flow.ui.screens.subscriptions

import app.zaid.flow.data.local.dao.SubscriptionGroupDao
import app.zaid.flow.data.local.entity.SubscriptionGroupEntity

internal suspend fun SubscriptionGroupDao.appendGroup(
    name: String,
    channelIds: List<String>,
) {
    insertGroup(
        SubscriptionGroupEntity(
            name = name,
            channelIds = channelIds.joinToString(","),
            sortOrder = getAllGroupsOnce().size,
        ),
    )
}

/** Returns false when no group is called [oldName]. A rename is a delete and insert because the name is the key. */
internal suspend fun SubscriptionGroupDao.editGroup(
    oldName: String,
    newName: String,
    channelIds: List<String>,
): Boolean {
    val existing = getAllGroupsOnce().find { it.name == oldName } ?: return false
    if (oldName != newName) {
        deleteGroup(oldName)
        insertGroup(existing.copy(name = newName, channelIds = channelIds.joinToString(",")))
    } else {
        updateGroup(existing.copy(channelIds = channelIds.joinToString(",")))
    }
    return true
}

internal suspend fun SubscriptionGroupDao.moveGroup(
    fromIndex: Int,
    toIndex: Int,
) {
    val groups = getAllGroupsOnce().toMutableList()
    if (fromIndex !in groups.indices || toIndex !in groups.indices || fromIndex == toIndex) return
    groups.add(toIndex, groups.removeAt(fromIndex))
    insertAll(groups.mapIndexed { index, group -> group.copy(sortOrder = index) })
}
