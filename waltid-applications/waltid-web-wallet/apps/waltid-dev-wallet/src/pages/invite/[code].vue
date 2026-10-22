<template>
  <div class="flex min-h-full items-center justify-center px-4 py-12">
    <div class="w-full max-w-md space-y-6 rounded-3xl bg-white p-6 shadow">
      <h1 class="text-2xl font-semibold text-gray-900">Invitation</h1>

      <LoadingIndicator v-if="pending">Checking the invite...</LoadingIndicator>

      <template v-else-if="invite">
        <p class="text-gray-700">
          You are invited to join <b>{{ invite.organizationName }}</b>.
          The invite is for <b>{{ invite.email }}</b>.
        </p>

        <template v-if="loggedIn">
          <button
            v-if="!errorMessage"
            :disabled="joining"
            class="block w-full rounded-md bg-blue-600 px-3 py-2 text-center text-sm font-semibold text-white hover:bg-blue-500 disabled:opacity-50"
            type="button"
            @click="join"
          >
            {{ joining ? "Joining..." : `Join ${invite.organizationName}` }}
          </button>
          <div v-else class="space-y-3">
            <p class="text-sm text-red-600" role="alert">{{ errorMessage }}</p>
            <button class="rounded-md bg-white px-3 py-2 text-sm font-semibold ring-1 ring-inset ring-gray-300 hover:bg-gray-50" type="button" @click="logoutAndRetry">
              Log in with {{ invite.email }}
            </button>
          </div>
        </template>

        <!-- The invited email already has an account: just log in -->
        <div v-else-if="invite.accountExists" class="space-y-3">
          <p class="text-sm text-gray-600">You already have an account. Log in and you join right away.</p>
          <NuxtLink
            :to="loginUrl"
            class="block w-full rounded-md bg-blue-600 px-3 py-2 text-center text-sm font-semibold text-white hover:bg-blue-500"
          >
            Log in as {{ invite.email }}
          </NuxtLink>
        </div>

        <div v-else class="space-y-3">
          <NuxtLink
            :to="`/signup?invite=${code}&email=${encodeURIComponent(invite.email)}`"
            class="block w-full rounded-md bg-blue-600 px-3 py-2 text-center text-sm font-semibold text-white hover:bg-blue-500"
          >
            Create an account
          </NuxtLink>
          <NuxtLink
            :to="loginUrl"
            class="block w-full rounded-md bg-white px-3 py-2 text-center text-sm font-semibold ring-1 ring-inset ring-gray-300 hover:bg-gray-50"
          >
            I already have an account
          </NuxtLink>
        </div>
      </template>

      <p v-else class="text-sm text-red-600" role="alert">{{ errorMessage }}</p>
    </div>
  </div>
</template>

<script setup>
import LoadingIndicator from "@waltid-web-wallet/components/loading/LoadingIndicator.vue";

const route = useRoute();
const { status, signOut } = useAuth();

const code = String(route.params.code);
const pending = ref(true);
const joining = ref(false);
const invite = ref(null);
const errorMessage = ref(null);
const loggedIn = computed(() => status.value === "authenticated");
const loginUrl = computed(() =>
  `/login?redirect=${encodeURIComponent(`/invite/${code}`)}` +
  (invite.value ? `&email=${encodeURIComponent(invite.value.email)}` : ""),
);

async function join() {
  joining.value = true;
  try {
    await $fetch("/wallet-api/organization/join", { method: "POST", body: { inviteCode: code } });
    await navigateTo("/organization");
  } catch (err) {
    errorMessage.value = err?.data?.message || "Could not join the organization.";
  } finally {
    joining.value = false;
  }
}

// Logged in with another account: log out, then log in with the invited email and come back here
async function logoutAndRetry() {
  await signOut({ callbackUrl: loginUrl.value });
}

onMounted(async () => {
  try {
    invite.value = await $fetch(`/wallet-api/organization/invite-info/${code}`);
  } catch (err) {
    errorMessage.value = err?.data?.message || "This invite is not valid.";
  } finally {
    pending.value = false;
  }
});

// Public page: people without an account open it too
definePageMeta({
  layout: "minimal",
  auth: false,
});

useHead({
  title: "Invitation - walt.id",
});
</script>
