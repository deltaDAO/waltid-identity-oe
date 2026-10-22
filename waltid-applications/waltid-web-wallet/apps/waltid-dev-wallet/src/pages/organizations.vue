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
        <p v-if="organization.deletionRequestedBy" class="rounded-md bg-red-50 p-2 text-sm text-red-800">
          {{ organization.deletionRequestedBy }} asked to delete this organization: open "Manage" to delete or decline.
        </p>
      </li>
    </ul>

    <section class="mt-10 space-y-2">
      <h2 class="text-lg font-semibold">Super admins</h2>
      <p class="text-sm text-gray-600">
        Super admins see and manage every organization.
        <span v-if="canManageSuperAdmins">As super admin from the server configuration, you add and remove the others.</span>
        <span v-else>Only super admins from the server configuration can add or remove super admins.</span>
        Only existing accounts can be added.
      </p>
      <form v-if="canManageSuperAdmins" class="flex flex-wrap items-center gap-2" @submit.prevent="addSuperAdmin">
        <input
          v-model="newSuperAdmin"
          class="rounded-md border-0 py-1.5 px-2 shadow-sm ring-1 ring-inset ring-gray-300 focus:ring-2 focus:ring-blue-600 sm:text-sm min-w-[16rem]"
          placeholder="name@company.com"
          required
          type="email"
        />
        <button class="rounded-md bg-blue-600 px-3 py-2 text-sm font-semibold text-white hover:bg-blue-500" type="submit">
          Add super admin
        </button>
      </form>
      <ul class="divide-y divide-gray-100 border rounded-2xl px-2" role="list">
        <li v-for="entry in superAdmins" :key="entry.accountId" class="flex flex-wrap items-center justify-between gap-2 py-3">
          <div class="min-w-0">
            <p class="truncate">{{ entry.email || "(no email)" }}</p>
            <p class="text-xs text-gray-500 font-mono truncate">{{ entry.accountId }}</p>
          </div>
          <div class="flex items-center gap-2">
            <span class="rounded-md px-1.5 py-0.5 text-xs font-medium ring-1 ring-inset">
              {{ entry.source === 'CONFIGURED' ? 'Server configuration' : 'Added' }}
            </span>
            <button
              v-if="canManageSuperAdmins && entry.source === 'ADDED'"
              class="rounded-md bg-red-500 px-2.5 py-1.5 text-sm font-semibold text-white hover:bg-red-600"
              type="button"
              @click="removeSuperAdmin(entry)"
            >
              Remove
            </button>
          </div>
        </li>
      </ul>
    </section>

    <p v-if="errorMessage" class="mt-4 text-sm text-red-600" role="alert">{{ errorMessage }}</p>
  </CenterMain>
</template>

<script setup>
import CenterMain from "@waltid-web-wallet/components/CenterMain.vue";
import LoadingIndicator from "@waltid-web-wallet/components/loading/LoadingIndicator.vue";

const pending = ref(true);
const organizations = ref([]);
const superAdmins = ref([]);
const canManageSuperAdmins = ref(false);
const newSuperAdmin = ref("");
const errorMessage = ref(null);

const activeMembers = (organization) => organization.members.filter((m) => m.status === "ACTIVE").length;
const pendingRequests = (organization) => organization.members.filter((m) => m.status === "PENDING").length;
const admins = (organization) =>
  organization.members
    .filter((m) => m.status === "ACTIVE" && m.role === "ADMIN")
    .map((m) => m.email || m.name)
    .join(", ");

async function load() {
  errorMessage.value = null;
  try {
    const mine = await $fetch("/wallet-api/organization");
    // Only for super admins: everyone else goes to their own organization
    if (!mine.superAdmin) {
      await navigateTo("/organization");
      return;
    }
    const [all, admins] = await Promise.all([
      $fetch("/wallet-api/organization/all"),
      $fetch("/wallet-api/organization/super-admins"),
    ]);
    organizations.value = all;
    superAdmins.value = admins;
    canManageSuperAdmins.value = mine.canManageSuperAdmins;
  } catch (err) {
    errorMessage.value = err?.data?.message || "Could not load the organizations.";
  } finally {
    pending.value = false;
  }
}

async function addSuperAdmin() {
  try {
    await $fetch("/wallet-api/organization/super-admins", { method: "POST", body: { email: newSuperAdmin.value } });
    newSuperAdmin.value = "";
    await load();
  } catch (err) {
    errorMessage.value = err?.data?.message || "Could not add the super admin.";
  }
}

async function removeSuperAdmin(entry) {
  if (!confirm(`Remove ${entry.email || entry.accountId} as super admin?`)) return;
  try {
    await $fetch(`/wallet-api/organization/super-admins/${entry.accountId}`, { method: "DELETE" });
    await load();
  } catch (err) {
    errorMessage.value = err?.data?.message || "Could not remove the super admin.";
  }
}

onMounted(load);

definePageMeta({
  layout: "default-reduced-nav",
  middleware: ["auth-required"],
});

useHead({
  title: "All organizations - walt.id",
});
</script>
