package io.github.mjohnson7cd.chronicleauto.features.account

import io.github.mjohnson7cd.chronicleauto.data.model.Account
import io.github.mjohnson7cd.chronicleauto.data.model.Library

/**
 * UI model combining an Account with its associated Libraries.
 * Used by AccountListAdapter for expandable list display.
 */
data class AccountWithLibraries(
    val account: Account,
    val libraries: List<Library>,
    val isExpanded: Boolean = false,
)
