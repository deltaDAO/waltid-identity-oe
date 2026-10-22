<script setup lang="ts">
import { onMounted } from "vue";
import { useRouter } from "vue-router";
import { useUserStore } from "@waltid-web-wallet/stores/user.ts";
import { storeToRefs } from "pinia";

definePageMeta({
  title: "Callback login to your wallet - walt.id",
  auth: false,
  layout: "minimal",
});

const router = useRouter();
const { status, data, signIn } = useAuth();
const userStore = useUserStore();
const { user } = storeToRefs(userStore);
const signInRedirectUrl = ref("/");
const { $auth } = useNuxtApp();

const isExchanging = ref(false);
const errorMessage = ref("");
const isLoading = ref(true);

onMounted(async () => {
  if (isExchanging.value) return;
  isExchanging.value = true;

  try {
    const route = useRoute();
    const code = route.query.code as string;
    const state = route.query.state as string;

    if (!code || !state) {
      errorMessage.value = "Missing authorization code or state. Redirecting to login...";
      setTimeout(() => router.push("/login"), 3000);
      return;
    }

    if (!$auth) {
      errorMessage.value = "Authentication plugin is not available. Please check your configuration.";
      isLoading.value = false;
      return;
    }

    const oidcState = await $auth.settings.stateStore!.get(state);
    const parsedState = oidcState ? JSON.parse(oidcState as string) : null;
    const codeVerifier = parsedState?.code_verifier ?? null;

    if (!codeVerifier) {
      errorMessage.value = "Session expired. Please try logging in again.";
      setTimeout(() => router.push("/login"), 3000);
      return;
    }

    const result = (await $fetch("/api/auth/callback", {
      method: "POST",
      body: { code, state, code_verifier: codeVerifier },
    })) as any;


    await signIn(
      { token: result.token },
      { callbackUrl: signInRedirectUrl.value }
    )
      .then(() => {
        user.value = {
          id: "",
          friendlyName: result.address,
        };
      })
      .catch((err) => {
        console.error("Could not sign in", err);
        errorMessage.value = "Sign in failed. Please try again.";
        isLoading.value = false;
      });
  } catch (error: any) {
    console.error("Callback error:", error);

    // Extract the server error message from $fetch error
    const serverMessage =
      error?.data?.message ||
      error?.data?.statusMessage ||
      error?.statusMessage ||
      error?.message ||
      "An unexpected error occurred.";

    const statusCode = error?.data?.statusCode || error?.statusCode || 500;

    errorMessage.value = `Error ${statusCode}: ${serverMessage}`;
    isLoading.value = false;
  }
});

function goToLogin() {
  router.push("/login");
}
</script>

<template>
  <div class="min-h-screen flex items-center justify-center p-4">
    <!-- Loading state -->
    <div v-if="isLoading && !errorMessage" class="text-center">
      <svg
        class="animate-spin h-10 w-10 text-blue-600 mx-auto mb-4"
        fill="none"
        viewBox="0 0 24 24"
        xmlns="http://www.w3.org/2000/svg"
      >
        <circle
          class="opacity-25"
          cx="12"
          cy="12"
          r="10"
          stroke="currentColor"
          stroke-width="4"
        />
        <path
          class="opacity-75"
          d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
          fill="currentColor"
        />
      </svg>
      <p class="text-gray-600 text-lg">Signing in with Signer Server...</p>
    </div>

    <!-- Error state -->
    <div
      v-if="errorMessage"
      class="w-full max-w-md bg-white rounded-lg shadow-lg overflow-hidden"
    >
      <div class="bg-red-50 border-l-4 border-red-500 p-6">
        <div class="flex items-start">
          <div class="flex-shrink-0">
            <svg
              class="h-6 w-6 text-red-500"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.072 16.5c-.77.833.192 2.5 1.732 2.5z"
              />
            </svg>
          </div>
          <div class="ml-4">
            <h3 class="text-lg font-semibold text-red-800">
              Authentication Failed
            </h3>
            <p class="mt-2 text-sm text-red-700">
              {{ errorMessage }}
            </p>
          </div>
        </div>
      </div>
      <div class="px-6 py-4 bg-white">
        <button
          class="w-full bg-blue-600 hover:bg-blue-500 text-white font-semibold py-2 px-4 rounded-md transition duration-200"
          @click="goToLogin"
        >
          Back to Login
        </button>
      </div>
    </div>
  </div>
</template>