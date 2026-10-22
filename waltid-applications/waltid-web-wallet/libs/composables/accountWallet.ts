import {navigateTo, useFetch, useRoute, useState} from "nuxt/app";
import {computed, watch} from "vue";

export type WalletListing = {
    id: string,
    name: string
    createdOn: string
    addedOn: string
    permission: string
}

export type WalletListings = {
    account: string,
    wallets: WalletListing[]
}

export async function listWallets() {
    const { data, refresh } = useFetch<WalletListings>("/wallet-api/wallet/accounts/wallets");
    await refresh()
    return data;
}

export function setWallet(
    newWallet: string | null,
    redirectUri: ((walletId: string) => string) | undefined = (walletId) => `/wallet/${walletId}`
) {
    useCurrentWallet().value = newWallet;

    if (newWallet != null && redirectUri != undefined)
        navigateTo(redirectUri(newWallet));
}

export function useCurrentWallet() {
    return useState<string | null>("wallet", () => {
        const currentRoute = useRoute();
        const currentWalletId = currentRoute.params["wallet"] as string ?? null;

        if (currentWalletId == null && currentRoute.name != "/") {
            console.log("Error for currentWallet at: ", currentRoute);
            return null
        } else {
            console.log("Returning: " + currentWalletId + ", at: " + currentRoute.fullPath);
            return currentWalletId;
        }
    });
}

/** Permission of the logged-in account on the current wallet (undefined while loading) */
export function useWalletPermission() {
    const currentWallet = useCurrentWallet();
    const { data } = useFetch<WalletListings>("/wallet-api/wallet/accounts/wallets", { key: "account-wallets" });
    return computed(() => data.value?.wallets?.find((wallet) => wallet.id === currentWallet.value)?.permission);
}

/**
 * Whether the logged-in account is an admin (ADMINISTRATE) of the current wallet.
 * Only admins see keys and DIDs and add credentials; members view and present credentials.
 */
export function useIsWalletAdmin() {
    const permission = useWalletPermission();
    return computed(() => permission.value === "ADMINISTRATE");
}

/** For admin-only wallet pages: members are sent to the wallet's credentials */
export function requireWalletAdmin() {
    const permission = useWalletPermission();
    const currentWallet = useCurrentWallet();
    watch(permission, (value) => {
        if (value && value !== "ADMINISTRATE") navigateTo(`/wallet/${currentWallet.value}`);
    }, { immediate: true });
}
