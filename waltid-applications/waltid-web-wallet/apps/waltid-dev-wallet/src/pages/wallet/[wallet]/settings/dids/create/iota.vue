<template>
  <DidCreationView method="iota" />
</template>

<script lang="ts" setup>
import {requireWalletAdmin} from "@waltid-web-wallet/composables/accountWallet.ts";
import DidCreationView from "@waltid-web-wallet/components/dids/DidCreationView.vue";
// Admins only: members are sent back to the wallet's credentials
requireWalletAdmin();

</script>
