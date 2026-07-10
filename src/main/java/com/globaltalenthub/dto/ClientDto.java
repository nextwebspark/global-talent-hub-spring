package com.globaltalenthub.dto;

import com.globaltalenthub.entity.AppClient;

/**
 * A resolved client: its own record, with the linked catalog company inlined for display
 * when {@code linkedCompanyId} is set ({@code linkedCompany} is null for an unlinked client).
 */
public record ClientDto(
    Long id,
    String name,
    String domain,
    Long linkedCompanyId,
    AppCompanyDto linkedCompany
) {
    public static ClientDto of(AppClient c, AppCompanyDto linked) {
        return new ClientDto(c.getId(), c.getName(), c.getDomain(), c.getLinkedCompanyId(), linked);
    }
}
