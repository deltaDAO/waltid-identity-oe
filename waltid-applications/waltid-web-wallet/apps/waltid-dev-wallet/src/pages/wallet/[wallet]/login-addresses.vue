<template>
  <CenterMain>
    <h1 class="text-2xl font-semibold mb-2">Login addresses</h1>
    <p class="text-gray-600 mb-4">
      Logging into the Enterprise market with one of these wallets opens this SSI wallet account.
      You can link as many addresses as you like.
    </p>

    <button
      class="inline-flex items-center rounded-md bg-blue-500 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-blue-600 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600"
      type="button"
      @click="linkLoginAddress"
    >
      <PlusIcon aria-hidden="true" class="-ml-0.5 mr-1.5 h-5 w-5" />
      Link login address
    </button>

    <LoadingIndicator v-if="pending" class="my-6">Loading login addresses...</LoadingIndicator>
    <p v-else-if="!loginAddresses || loginAddresses.length === 0" class="mt-4 font-semibold">
      No login addresses yet
    </p>
    <ul v-else class="divide-y divide-gray-100 border rounded-2xl mt-4 px-2" role="list">
      <li
        v-for="loginAddress in loginAddresses"
        :key="loginAddress.address"
        class="flex items-center justify-between gap-x-6 py-4"
      >
        <span class="truncate text-base font-normal">{{ loginAddress.address }}</span>
        <button
          class="rounded-md bg-red-500 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-red-600"
          type="button"
          @click="removeLoginAddress(loginAddress.address)"
        >
          Remove
        </button>
      </li>
    </ul>
    <p v-if="errorMessage" class="text-sm text-red-600 mt-2" role="alert">{{ errorMessage }}</p>
  </CenterMain>
</template>

<script setup>
import CenterMain from "@waltid-web-wallet/components/CenterMain.vue";
import LoadingIndicator from "@waltid-web-wallet/components/loading/LoadingIndicator.vue";
import useModalStore from "@waltid-web-wallet/stores/useModalStore.ts";
import {PlusIcon} from "@heroicons/vue/20/solid";
import LinkLoginAddressModal from "~/components/login-addresses/LinkLoginAddressModal.vue";

const modalStore = useModalStore();

const {
  data: loginAddresses,
  pending,
  refresh,
} = await useLazyFetch("/wallet-api/auth/login-addresses");
const errorMessage = ref(null);

function linkLoginAddress() {
  errorMessage.value = null;
  modalStore.openModal({
    component: LinkLoginAddressModal,
    props: {
      callback: () => refresh(),
    },
  });
}

async function removeLoginAddress(address) {
  errorMessage.value = null;
  if (!confirm(`Remove ${address}? Logging into the market with it will no longer open this account.`)) return;
  await $fetch(`/wallet-api/auth/login-addresses/${address}`, { method: "DELETE" })
    .then(() => refresh())
    .catch((err) => {
      errorMessage.value = err?.data?.message || "Could not remove the address.";
    });
}

useHead({
  title: "Login addresses - walt.id",
});
</script>
