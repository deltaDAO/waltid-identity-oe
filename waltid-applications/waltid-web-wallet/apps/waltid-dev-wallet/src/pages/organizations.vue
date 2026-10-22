<template>
  <CenterMain>
    <h1 class="text-2xl font-semibold mb-2">All organizations</h1>
    <p class="text-sm text-gray-600 mb-4">
      As super admin you see every organization and can manage it like its admin.
    </p>

    <LoadingIndicator v-if="pending">Loading organizations...</LoadingIndicator>
    <p v-else-if="organizations.length === 0" class="font-semibold">No organizations yet</p>
    <ul v-else class="space-y-3" role="list">
      <li v-for="organization in organizations" :key="organization.id" class="border rounded-2xl p-3 space-y-2">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <div class="min-w-0">
            <p class="font-semibold truncate">{{ organization.name }}</p>
            <p class="text-xs text-gray-500">
              {{ activeMembers(organization) }} member(s), admins: {{ admins(organization) || "none" }}
              <span v-if="pendingRequests(organization) > 0">, {{ pendingRequests(organization) }} waiting to join</span>
            </p>
          </div>
          <NuxtLink
            :to="`/organization?org=${organization.id}`"
            class="rounded-md bg-white px-2.5 py-1.5 text-sm font-semibold ring-1 ring-inset ring-gray-300 hover:bg-gray-50"
          >
            Manage
          </NuxtLink>
        </div>
        <p v-if="organization.pendingCreatorChange" class="rounded-md bg-yellow-50 p-2 text-sm text-yellow-800">
          {{ organization.pendingCreatorChange.requestedBy || "An admin" }} asks to
          {{ organization.pendingCreatorChange.change === 'REMOVE' ? 'remove the creator' : 'make the creator a user' }}:
          open "Manage" to confirm or decline.
        </p>
      </li>
    </ul>

    <p v-if="errorMessage" class="mt-4 text-sm text-red-600" role="alert">{{ errorMessage }}</p>
  </CenterMain>
</template>

<script setup>
import CenterMain from "@waltid-web-wallet/components/CenterMain.vue";
import LoadingIndicator from "@waltid-web-wallet/components/loading/LoadingIndicator.vue";

const pending = ref(true);
const organizations = ref([]);
const errorMessage = ref(null);

const activeMembers = (organization) => organization.members.filter((m) => m.status === "ACTIVE").length;
const pendingRequests = (organization) => organization.members.filter((m) => m.status === "PENDING").length;
const admins = (organization) =>
  organization.members
    .filter((m) => m.status === "ACTIVE" && m.role === "ADMIN")
    .map((m) => m.email || m.name)
    .join(", ");

onMounted(async () => {
  try {
    organizations.value = await $fetch("/wallet-api/organization/all");
  } catch (err) {
    errorMessage.value = err?.data?.message || "Could not load the organizations.";
  } finally {
    pending.value = false;
  }
});

definePageMeta({
  layout: "default-reduced-nav",
});

useHead({
  title: "All organizations - walt.id",
});
</script>
