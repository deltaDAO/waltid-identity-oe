import {useLocalStorage} from "@vueuse/core";
import {defineStore} from "pinia";
import {ref} from "vue";

export const useUserStore = defineStore("userStore", () => {
    const user = ref(useLocalStorage("id/walt/wallet/user", { id: "", friendlyName: "" }));

    /** Load the logged-in account's name (or email) from the wallet API, instead of relying on what login stored */
    async function refreshUser() {
        try {
            const response = await fetch("/wallet-api/auth/user-info");
            if (response.status === 401) {
                // Logged in with an account that no longer exists: log out and start over at the login page
                await fetch("/wallet-api/auth/logout", { method: "POST" }).catch(() => {});
                user.value = { id: "", friendlyName: "" };
                window.location.assign("/login");
                return;
            }
            if (!response.ok) return;
            const account = await response.json();
            user.value = { id: account.id ?? "", friendlyName: account.name || account.email || "" };
        } catch {
            // Not logged in or API unreachable: keep what is stored
        }
    }

    return { user, refreshUser };
});
