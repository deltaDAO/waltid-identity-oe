<template>
  <CenterMain>
    <h1 class="text-2xl font-semibold mb-4">Your profile</h1>

    <LoadingIndicator v-if="pending">Loading profile...</LoadingIndicator>
    <dl v-else class="divide-y divide-gray-100 border rounded-2xl px-4 max-w-xl">
      <div class="py-3 sm:grid sm:grid-cols-3 sm:gap-4">
        <dt class="font-medium text-gray-900">Name</dt>
        <dd class="mt-1 sm:col-span-2 sm:mt-0">{{ account?.name || "—" }}</dd>
      </div>
      <div class="py-3 sm:grid sm:grid-cols-3 sm:gap-4">
        <dt class="font-medium text-gray-900">Email</dt>
        <dd class="mt-1 sm:col-span-2 sm:mt-0">{{ account?.email || "—" }}</dd>
      </div>
      <div class="py-3 sm:grid sm:grid-cols-3 sm:gap-4">
        <dt class="font-medium text-gray-900">Account ID</dt>
        <dd class="mt-1 sm:col-span-2 sm:mt-0 font-mono text-sm break-all">{{ account?.id || "—" }}</dd>
      </div>
      <div class="py-3 sm:grid sm:grid-cols-3 sm:gap-4">
        <dt class="font-medium text-gray-900">Organization</dt>
        <dd class="mt-1 sm:col-span-2 sm:mt-0">
          <template v-if="organization">
            {{ organization.name }}
            <span class="text-gray-500">
              ({{ organization.status === 'PENDING' ? 'waiting for approval' : organization.role === 'ADMIN' ? 'admin' : 'user' }})
            </span>
          </template>
          <span v-else>None yet</span>
          <NuxtLink class="ml-2 text-blue-600 underline" to="/organization">Open</NuxtLink>
        </dd>
      </div>
    </dl>
    <p class="mt-4 text-sm text-gray-600 max-w-xl">
      Wallets and their addresses belong to your organization. Its admins manage them under Organization.
    </p>
  </CenterMain>
</template>

<script setup>
import CenterMain from "@waltid-web-wallet/components/CenterMain.vue";
import LoadingIndicator from "@waltid-web-wallet/components/loading/LoadingIndicator.vue";

const pending = ref(true);
const account = ref(null);
const organization = ref(null);

onMounted(async () => {
  try {
    const [user, mine] = await Promise.all([$fetch("/wallet-api/auth/user-info"), $fetch("/wallet-api/organization")]);
    account.value = user;
    organization.value = mine.organization;
  } finally {
    pending.value = false;
  }
});

definePageMeta({
  layout: "default-reduced-nav",
  middleware: ["auth-required"],
});

useHead({
  title: "Your profile - walt.id",
});
</script>
