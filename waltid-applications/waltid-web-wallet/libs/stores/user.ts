import {useLocalStorage} from "@vueuse/core";
import {defineStore} from "pinia";
import {ref} from "vue";

export const useUserStore = defineStore("userStore", () => {
    const user = ref(useLocalStorage("id/walt/wallet/user", { id: "", friendlyName: "" }));

    /** Load the logged-in account's name (or email) from the wallet API, instead of relying on what login stored */
    async function refreshUser() {
        try {
            const response = await fetch("/wallet-api/auth/user-info");
            if (!response.ok) return;
            const account = await response.json();
            user.value = { id: account.id ?? "", friendlyName: account.name || account.email || "" };
        } catch {
            // Not logged in or API unreachable: keep what is stored
        }
    }

    return { user, refreshUser };
});
