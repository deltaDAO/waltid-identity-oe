<template>
  <CenterMain>
    <h1 class="font-semibold">Profile</h1>
    <p>Username: {{ user.friendlyName }}</p>

    <!-- login-addresses: EVM addresses that log into this account (e.g. from the Enterprise market) -->
    <h2 class="font-semibold mt-6">Login addresses</h2>
    <p class="text-sm text-gray-600">
      Logging into the Enterprise market with one of these wallets opens this SSI wallet account.
    </p>
    <div class="mt-4">
      <button
        class="inline-flex items-center rounded-md bg-blue-500 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-blue-600 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600"
        type="button"
        @click="linkLoginAddress"
      >
        <PlusIcon aria-hidden="true" class="-ml-0.5 mr-1.5 h-5 w-5" />
        Link login address
      </button>
    </div>
    <LoadingIndicator v-if="loginAddressesPending">Loading</LoadingIndicator>
    <h3 v-else-if="!loginAddresses || loginAddresses.length === 0" class="font-semibold mt-2">
      No login addresses
    </h3>
    <ul
      v-else
      class="divide-y divide-gray-100 border rounded-2xl mt-2 px-2"
      role="list"
    >
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
    <p v-if="loginAddressError" class="text-sm text-red-600 mt-2" role="alert">{{ loginAddressError }}</p>

    <h2 class="font-semibold mt-8">Watched addresses</h2>
    <!-- add-wallet -->
    <div class="items-center">
      <div class="mt-4">
        <button
          class="inline-flex items-center rounded-md bg-blue-500 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-blue-600 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600"
          type="button"
          @click="addWallet"
        >
          <PlusIcon aria-hidden="true" class="-ml-0.5 mr-1.5 h-5 w-5" />
          Add watched address
        </button>
      </div>
    </div>
    <LoadingIndicator v-if="pending">Loading</LoadingIndicator>
    <span v-else-if="wallets && wallets.length > 0" class="font-semibold"
      >Your watched addresses ({{ wallets.length }}):</span
    >
    <h3 v-else class="font-semibold">No watched addresses</h3>
    <!-- wallet-list -->
    <ul
      class="divide-y divide-gray-100 list-decimal border rounded-2xl mt-2 px-2"
      role="list"
    >
      <li
        v-for="wallet in wallets"
        :key="wallet.id"
        class="flex items-center justify-between gap-x-6 py-4"
      >
        <div class="flex w-full">
          <Web3WalletIcon :ecosystem="wallet.ecosystem" class="px-2" />
          <div class="flex truncate w-full">
            <span class="text-base font-normal">{{ wallet.address }}</span>
          </div>
          <div class="flex flex-row space-x-1 items-center">
            <!-- connect -->
            <button
              :class="{ 'bg-red-500 hover:bg-red-600': wallet.owner }"
              class="w-full rounded-md bg-blue-500 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-blue-600 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600"
              type="button"
              @click="
                wallet.owner
                  ? disconnectWallet(wallet.id)
                  : connectWallet(wallet.id)
              "
            >
              <span v-if="wallet.owner">Disconnect</span>
              <span v-else>Connect</span>
            </button>
            <!-- unlink -->
            <button
              :class="{ 'opacity-25': wallet.owner }"
              :disabled="wallet.owner"
              class="w-full rounded-md bg-indigo-500 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-600 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600"
              type="button"
              @click="unlinkWallet(wallet.id)"
            >
              <span>Unlink</span>
            </button>
          </div>
        </div>
      </li>
    </ul>
  </CenterMain>
</template>

<script setup>
import CenterMain from "@waltid-web-wallet/components/CenterMain.vue";
import {PlusIcon} from "@heroicons/vue/20/solid";
import Web3WalletIcon from "@waltid-web-wallet/components/Web3WalletIcon.vue";
import AddWalletModal from "@waltid-web-wallet/components/modals/AddWalletModal.vue";
import LinkLoginAddressModal from "~/components/profile/LinkLoginAddressModal.vue";
import {useCurrentWallet} from "@waltid-web-wallet/composables/accountWallet.ts";
import useModalStore from "@waltid-web-wallet/stores/useModalStore.ts";
import {useUserStore} from "@waltid-web-wallet/stores/user.ts";
import {storeToRefs} from "pinia";

const modalStore = useModalStore();
const userStore = useUserStore();

const currentWallet = useCurrentWallet();

const { user } = storeToRefs(userStore);
const {
  data: wallets,
  pending,
  refresh,
  error,
} = await useLazyFetch(
  `/wallet-api/wallet/${currentWallet.value}/web3accounts`,
);

const {
  data: loginAddresses,
  pending: loginAddressesPending,
  refresh: refreshLoginAddresses,
} = await useLazyFetch("/wallet-api/auth/login-addresses");
const loginAddressError = ref(null);

function linkLoginAddress() {
  loginAddressError.value = null;
  modalStore.openModal({
    component: LinkLoginAddressModal,
    props: {
      callback: () => refreshLoginAddresses(),
    },
  });
}

async function removeLoginAddress(address) {
  loginAddressError.value = null;
  if (!confirm(`Remove ${address}? Logging into the market with it will no longer open this account.`)) return;
  await $fetch(`/wallet-api/auth/login-addresses/${address}`, { method: "DELETE" })
    .then(() => refreshLoginAddresses())
    .catch((err) => {
      loginAddressError.value = err?.data?.message || "Could not remove the address.";
    });
}

function addWallet() {
  modalStore.openModal({
    component: AddWalletModal,
    props: {
      callback: (wallet) => {
        if (
          wallets.value.filter(function (e) {
            return e.id === wallet.id;
          }).length == 0
        ) {
          wallets.value.push(wallet);
        }
      },
    },
  });
}

function connectWallet(walletId) {
  console.log(`Connecting wallet: ${walletId}`);
  modalStore.openModal({
    component: ConnectWalletModal,
  });
  //TODO
}

async function disconnectWallet(walletId) {
  console.log(`Disconnecting wallet: ${walletId}`);
  await $fetch(
    `/wallet-api/wallet/${currentWallet.value}/web3accounts/disconnect`,
    {
      method: "POST",
      body: walletId,
    },
  )
    .then((response) => {
      // refresh list
      refreshNuxtData();
    })
    .catch((err) => {
      console.error(`Error disconnecting wallet: ${err}`);
    });
}

async function unlinkWallet(walletId) {
  console.log(`Unlinking wallet: ${walletId}`);
  await $fetch(
    `/wallet-api/wallet/${currentWallet.value}/web3accounts/unlink`,
    {
      method: "POST",
      body: walletId,
    },
  )
    .then((response) => {
      // refresh list
      refreshNuxtData();
    })
    .catch((err) => {
      console.error(`Error unlinking wallet: ${err}`);
    });
}
</script>

<style scoped></style>
