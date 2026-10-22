<template>
  <div>
    <CloseButton />
    <div class="p-4 space-y-6">
      <div>
        <h3 class="text-base font-semibold text-gray-900 dark:text-white">Link a login address</h3>
        <p class="mt-1 text-sm text-gray-600 dark:text-gray-300">
          After linking, logging into the Enterprise market with this wallet opens this SSI wallet account.
          Your wallet file and password stay in this browser: only a signature is sent.
        </p>
      </div>

      <form class="space-y-4" @submit.prevent="linkKeystore">
        <div>
          <label class="block text-sm font-medium leading-6 text-gray-900 dark:text-white" for="keystore-file">
            <span class="flex flex-row items-center">
              <DocumentTextIcon class="h-5 mr-1" />
              JSON wallet file
            </span>
          </label>
          <input
            id="keystore-file"
            ref="fileInput"
            :disabled="isProgress"
            accept=".json,application/json"
            class="mt-2 block w-full text-sm text-gray-900 dark:text-white"
            required
            type="file"
            @change="onFileSelected"
          />
          <p v-if="fileAddress" class="mt-1 text-xs text-gray-500 dark:text-gray-400 break-all">
            Address in file: {{ fileAddress }}
          </p>
        </div>

        <div>
          <label class="block text-sm font-medium leading-6 text-gray-900 dark:text-white" for="keystore-password">
            <span class="flex flex-row items-center">
              <KeyIcon class="h-5 mr-1" />
              Wallet file password
            </span>
          </label>
          <input
            id="keystore-password"
            v-model="password"
            :disabled="isProgress"
            autocomplete="off"
            class="mt-2 block w-full rounded-md border-0 py-1.5 shadow-sm ring-1 ring-inset ring-gray-300 text-gray-900 dark:text-white placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6 px-2 bg-white dark:bg-gray-700"
            required
            type="password"
          />
        </div>

        <div v-if="decryptProgress !== null" class="w-full">
          <div class="text-xs text-gray-600 dark:text-gray-300 mb-1">
            Decrypting wallet file... {{ Math.round(decryptProgress * 100) }}%
          </div>
          <div class="h-2 w-full rounded bg-gray-200">
            <div :style="{ width: `${decryptProgress * 100}%` }" class="h-2 rounded bg-blue-500" />
          </div>
        </div>

        <button
          :disabled="isProgress"
          class="inline-flex w-full justify-center items-center rounded-md bg-blue-500 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-blue-600 disabled:opacity-50"
          type="submit"
        >
          Link wallet file
        </button>
      </form>

      <div class="flex items-center gap-x-2 text-xs text-gray-500">
        <div class="h-px flex-1 bg-gray-200" />
        or
        <div class="h-px flex-1 bg-gray-200" />
      </div>

      <button
        :disabled="isProgress"
        class="inline-flex w-full justify-center items-center rounded-md border border-blue-500 px-3 py-2 text-sm font-semibold text-blue-600 shadow-sm hover:bg-blue-50 disabled:opacity-50"
        type="button"
        @click="linkMetaMask"
      >
        Link with MetaMask
      </button>

      <p v-if="errorMessage" class="text-sm text-red-600" role="alert">{{ errorMessage }}</p>
    </div>
  </div>
</template>

<script lang="ts" setup>
import {DocumentTextIcon, KeyIcon} from "@heroicons/vue/20/solid";
import CloseButton from "@waltid-web-wallet/components/modals/CloseButton.vue";
import useModalStore from "@waltid-web-wallet/stores/useModalStore.ts";
import {isKeystoreJson, Wallet} from "ethers";
import {MetaMaskSDK} from "@metamask/sdk";
import {onBeforeUnmount, ref} from "vue";

const props = defineProps<{
  callback: () => void;
}>();

const store = useModalStore();

const fileInput = ref<HTMLInputElement | null>(null);
const password = ref("");
const fileAddress = ref<string | null>(null);
const decryptProgress = ref<number | null>(null);
const isProgress = ref(false);
const errorMessage = ref<string | null>(null);

// Kept in memory only for the duration of the link, never stored
let keystoreJson: string | null = null;

async function onFileSelected(event: Event) {
  errorMessage.value = null;
  fileAddress.value = null;
  keystoreJson = null;

  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;

  const text = await file.text();
  if (!isKeystoreJson(text)) {
    errorMessage.value = "This file is not an encrypted JSON wallet file (keystore).";
    return;
  }
  keystoreJson = text;

  const address = JSON.parse(text).address as string | undefined;
  if (address) fileAddress.value = address.startsWith("0x") ? address : `0x${address}`;
}

function clearSecrets() {
  keystoreJson = null;
  password.value = "";
  fileAddress.value = null;
  if (fileInput.value) fileInput.value.value = "";
}

onBeforeUnmount(clearSecrets);

function apiErrorMessage(err: any): string {
  return err?.data?.message || err?.message || "Something went wrong.";
}

async function postSignedNonce(sign: (nonce: string) => Promise<{ address: string; signed: string }>) {
  const nonce = await $fetch<string>("/wallet-api/auth/account/web3/nonce", {responseType: "text"});
  const {address, signed} = await sign(nonce);

  try {
    await $fetch("/wallet-api/auth/login-addresses", {
      method: "POST",
      body: {challenge: nonce, signed, publicKey: address},
    });
  } catch (err: any) {
    if (err?.response?.status === 409) {
      throw new Error(
        "This address already has its own wallet account (it has logged into the market before), so it can't be linked here.",
      );
    }
    throw new Error(apiErrorMessage(err));
  }

  store.closeModal();
  props.callback();
}

async function linkKeystore() {
  errorMessage.value = null;
  if (!keystoreJson) {
    errorMessage.value = "Please select an encrypted JSON wallet file.";
    return;
  }

  isProgress.value = true;
  try {
    let wallet: Awaited<ReturnType<typeof Wallet.fromEncryptedJson>> | null = null;
    try {
      decryptProgress.value = 0;
      wallet = await Wallet.fromEncryptedJson(keystoreJson, password.value, (p) => {
        decryptProgress.value = p;
      });
    } catch (err: any) {
      throw new Error("Could not decrypt the wallet file. Please check the password.");
    } finally {
      decryptProgress.value = null;
    }

    try {
      await postSignedNonce(async (nonce) => ({
        address: wallet!.address,
        signed: await wallet!.signMessage(nonce),
      }));
    } finally {
      wallet = null;
    }
  } catch (err: any) {
    errorMessage.value = err.message;
  } finally {
    // Keep the selected file so a wrong password can be retried; it is dropped when the modal closes
    password.value = "";
    isProgress.value = false;
  }
}

async function linkMetaMask() {
  errorMessage.value = null;
  isProgress.value = true;
  try {
    const MMSDK = new MetaMaskSDK({
      dappMetadata: {name: "Walt.id Web Wallet", url: window.location.href},
      injectProvider: true,
    });
    await MMSDK.connect();
    const ethereum = MMSDK.getProvider();
    if (!ethereum) throw new Error("MetaMask is not available in this browser.");

    await postSignedNonce(async (nonce) => {
      const accounts = (await ethereum.request({method: "eth_requestAccounts"})) as string[];
      const address = accounts[0];
      const signed = (await ethereum.request({method: "personal_sign", params: [nonce, address]})) as string;
      return {address, signed};
    });
  } catch (err: any) {
    errorMessage.value = err?.message || "Could not link the MetaMask address.";
  } finally {
    isProgress.value = false;
  }
}
</script>
