<template>
  <CenterMain>
    <h1 class="font-semibold">Settings main</h1>

    <p>
      Additional settings can be added here, as to not clutter the sidebar menus
      or profile menus.
    </p>
  </CenterMain>
</template>

<script setup>
import {requireWalletAdmin} from "@waltid-web-wallet/composables/accountWallet.ts";
import CenterMain from "@waltid-web-wallet/components/CenterMain.vue";
// Admins only: members are sent back to the wallet's credentials
requireWalletAdmin();

</script>

<style scoped></style>
