<template>
  <CenterMain>
    <div class="flex flex-wrap items-center justify-between gap-3 mb-2">
      <h1 class="text-2xl font-semibold">{{ managedOrganization ? managedOrganization.name : "Organization" }}</h1>
      <NuxtLink v-if="superAdmin" class="text-sm text-blue-600 underline" to="/organizations">
        All organizations (super admin)
      </NuxtLink>
    </div>
    <p v-if="managedOrganization" class="mb-4 text-sm text-gray-600">
      You manage this organization as super admin.
    </p>

    <LoadingIndicator v-if="pending">Loading organization...</LoadingIndicator>

    <!-- No organization yet: create one or join -->
    <div v-else-if="!adminView && !organization" class="space-y-8">
      <p class="text-gray-600">
        Wallets belong to organizations. Create an organization for your company, or join one.
      </p>

      <form class="space-y-3 max-w-lg" @submit.prevent="createOrganization">
        <h2 class="text-lg font-semibold">Create an organization</h2>
        <input
          v-model="newName"
          class="block w-full rounded-md border-0 py-1.5 px-2 shadow-sm ring-1 ring-inset ring-gray-300 focus:ring-2 focus:ring-blue-600 sm:text-sm"
          placeholder="Organization name"
          required
          type="text"
        />
        <button :disabled="busy" class="rounded-md bg-blue-600 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-blue-500 disabled:opacity-50" type="submit">
          Create organization
        </button>
        <p class="text-xs text-gray-500">You become its admin.</p>
      </form>

      <form class="space-y-3 max-w-lg" @submit.prevent="joinOrganization">
        <h2 class="text-lg font-semibold">Join an organization</h2>
        <input
          v-model="joinValue"
          class="block w-full rounded-md border-0 py-1.5 px-2 shadow-sm ring-1 ring-inset ring-gray-300 focus:ring-2 focus:ring-blue-600 sm:text-sm"
          placeholder="Invite code or exact organization name"
          required
          type="text"
        />
        <button :disabled="busy" class="rounded-md bg-white px-3 py-2 text-sm font-semibold text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 hover:bg-gray-50 disabled:opacity-50" type="submit">
          Join
        </button>
        <p class="text-xs text-gray-500">
          With an invite from an admin you join right away; the invite only works for your email address.
          With the organization's name, an admin has to accept your request.
        </p>
      </form>
    </div>

    <!-- Waiting for approval -->
    <div v-else-if="!adminView && organization.status === 'PENDING'" class="rounded-md bg-yellow-50 p-4 text-yellow-800">
      Your request to join <b>{{ organization.name }}</b> is waiting for an admin to accept it.
    </div>

    <!-- Member -->
    <div v-else-if="!adminView" class="space-y-2">
      <p>You are a member of <b>{{ organization.name }}</b>.</p>
      <p class="text-gray-600">
        The wallets your admins give you access to appear under <NuxtLink class="text-blue-600 underline" to="/">Select wallet</NuxtLink>.
      </p>
    </div>

    <!-- Admin (of the own organization, or a super admin managing any organization) -->
    <div v-else class="space-y-10">
      <p v-if="!managedOrganization">You are an admin of <b>{{ organization.name }}</b>.</p>

      <section class="space-y-2">
        <h2 class="text-lg font-semibold">Invite people</h2>
        <p class="text-sm text-gray-600">
          Each invite is for one email address and works once, for 14 days. Send the link to that person:
          they join as soon as they open it while logged in with that email address.
        </p>
        <form class="flex flex-wrap items-center gap-2" @submit.prevent="createInvite">
          <input
            v-model="inviteEmail"
            class="rounded-md border-0 py-1.5 px-2 shadow-sm ring-1 ring-inset ring-gray-300 focus:ring-2 focus:ring-blue-600 sm:text-sm min-w-[16rem]"
            placeholder="name@company.com"
            required
            type="email"
          />
          <button :disabled="busy" class="rounded-md bg-blue-600 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-blue-500 disabled:opacity-50" type="submit">
            Create invite
          </button>
        </form>
        <ul v-if="invites.length > 0" class="divide-y divide-gray-100 border rounded-2xl px-2 mt-2" role="list">
          <li v-for="invite in invites" :key="invite.code" class="flex flex-wrap items-center justify-between gap-2 py-3">
            <div class="min-w-0">
              <p class="font-medium truncate">{{ invite.email }}</p>
              <p class="text-xs text-gray-500">Valid until {{ formatDate(invite.expiresOn) }}</p>
            </div>
            <div class="flex flex-wrap gap-2">
              <button class="rounded-md bg-white px-2.5 py-1.5 text-sm font-semibold ring-1 ring-inset ring-gray-300 hover:bg-gray-50" type="button" @click="copyInviteLink(invite)">
                {{ copiedCode === invite.code ? "Copied" : "Copy link" }}
              </button>
              <button class="rounded-md bg-red-500 px-2.5 py-1.5 text-sm font-semibold text-white hover:bg-red-600" type="button" @click="revokeInvite(invite)">
                Revoke
              </button>
            </div>
          </li>
        </ul>
      </section>

      <section class="space-y-2">
        <h2 class="text-lg font-semibold">Members</h2>
        <ul class="divide-y divide-gray-100 border rounded-2xl px-2" role="list">
          <li v-for="member in members" :key="member.accountId" class="flex flex-wrap items-center justify-between gap-3 py-3">
            <div class="min-w-0">
              <p class="font-medium truncate">{{ member.name || member.email }}</p>
              <p class="text-xs text-gray-500 truncate">{{ member.email }}</p>
            </div>
            <div class="flex flex-wrap items-center gap-2">
              <span v-if="member.creator" class="rounded-md bg-blue-50 px-1.5 py-0.5 text-xs font-medium text-blue-700 ring-1 ring-inset ring-blue-200">Creator</span>
              <span v-if="member.pendingChange" class="rounded-md bg-yellow-50 px-1.5 py-0.5 text-xs font-medium text-yellow-800 ring-1 ring-inset ring-yellow-200">
                {{ member.pendingChange === 'REMOVE' ? 'Removal' : 'Change to user' }} waiting for confirmation
              </span>
              <span class="rounded-md px-1.5 py-0.5 text-xs font-medium ring-1 ring-inset">
                {{ member.status === 'PENDING' ? 'Wants to join' : member.role === 'ADMIN' ? 'Admin' : 'User' }}
              </span>
              <button v-if="member.status === 'PENDING'" class="rounded-md bg-blue-600 px-2.5 py-1.5 text-sm font-semibold text-white hover:bg-blue-500" type="button" @click="approve(member)">
                Accept
              </button>
              <button v-else-if="member.role === 'USER'" class="rounded-md bg-white px-2.5 py-1.5 text-sm font-semibold ring-1 ring-inset ring-gray-300 hover:bg-gray-50" type="button" @click="setRole(member, 'ADMIN')">
                Make admin
              </button>
              <button v-else class="rounded-md bg-white px-2.5 py-1.5 text-sm font-semibold ring-1 ring-inset ring-gray-300 hover:bg-gray-50" type="button" @click="setRole(member, 'USER')">
                Make user
              </button>
              <button class="rounded-md bg-red-500 px-2.5 py-1.5 text-sm font-semibold text-white hover:bg-red-600" type="button" @click="removeMember(member)">
                {{ member.status === 'PENDING' ? 'Decline' : 'Remove' }}
              </button>
            </div>
          </li>
        </ul>
        <!-- Super admins decide pending changes of the creator from here -->
        <div v-if="managedOrganization && pendingChange" class="flex flex-wrap items-center gap-2 rounded-md bg-yellow-50 p-3 text-sm text-yellow-800">
          {{ pendingChangeText }}
          <button class="rounded-md bg-blue-600 px-2.5 py-1.5 font-semibold text-white hover:bg-blue-500" type="button" @click="decideCreatorChange('confirm')">Confirm</button>
          <button class="rounded-md bg-white px-2.5 py-1.5 font-semibold ring-1 ring-inset ring-gray-300 hover:bg-gray-50" type="button" @click="decideCreatorChange('decline')">Decline</button>
        </div>
      </section>

      <section class="space-y-2">
        <h2 class="text-lg font-semibold">Addresses and wallets</h2>
        <p class="text-sm text-gray-600">
          Each address has its own wallet. Logging into the Enterprise market with the address opens that wallet.
          Choose which members may use it; admins always can.
        </p>
        <button class="inline-flex items-center rounded-md bg-blue-500 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-blue-600" type="button" @click="addAddress">
          <PlusIcon aria-hidden="true" class="-ml-0.5 mr-1.5 h-5 w-5" />
          Add address
        </button>

        <p v-if="addresses.length === 0" class="text-sm font-semibold mt-2">No addresses yet</p>
        <ul v-else class="space-y-3 mt-2" role="list">
          <li v-for="entry in addresses" :key="entry.address" class="border rounded-2xl p-3 space-y-2">
            <div class="flex flex-wrap items-center justify-between gap-2">
              <div class="min-w-0">
                <p class="font-mono text-sm break-all">{{ entry.address }}</p>
                <p class="text-xs text-gray-500">{{ entry.walletName }}</p>
              </div>
              <button class="rounded-md bg-red-500 px-2.5 py-1.5 text-sm font-semibold text-white hover:bg-red-600" type="button" @click="removeAddress(entry)">
                Remove
              </button>
            </div>
            <div v-if="users.length > 0" class="flex flex-wrap gap-x-5 gap-y-1">
              <label v-for="member in users" :key="member.accountId" class="inline-flex items-center gap-1.5 text-sm">
                <input
                  :checked="entry.access.includes(member.accountId)"
                  type="checkbox"
                  @change="toggleAccess(entry, member, $event.target.checked)"
                />
                {{ member.name || member.email }}
              </label>
            </div>
            <p v-else class="text-xs text-gray-500">No members to give access to yet.</p>
          </li>
        </ul>
      </section>

      <!-- Deleting: the creator asks, a super admin confirms (or deletes directly) -->
      <section v-if="managedOrganization || organization?.creator" class="space-y-2">
        <h2 class="text-lg font-semibold">Delete organization</h2>
        <p class="text-sm text-gray-600">
          Deletes the organization, all wallets of its addresses (with keys, DIDs and credentials), its members and invites.
          This cannot be undone.
        </p>
        <template v-if="managedOrganization">
          <p v-if="managedOrganization.deletionRequestedBy" class="rounded-md bg-yellow-50 p-2 text-sm text-yellow-800">
            {{ managedOrganization.deletionRequestedBy }} asked to delete this organization.
          </p>
          <div class="flex flex-wrap gap-2">
            <button class="rounded-md bg-red-600 px-3 py-2 text-sm font-semibold text-white hover:bg-red-500" type="button" @click="deleteOrganization">
              Delete organization
            </button>
            <button v-if="managedOrganization.deletionRequestedBy" class="rounded-md bg-white px-3 py-2 text-sm font-semibold ring-1 ring-inset ring-gray-300 hover:bg-gray-50" type="button" @click="declineDeletion">
              Decline request
            </button>
          </div>
        </template>
        <template v-else>
          <div v-if="organization.deletionRequested" class="flex flex-wrap items-center gap-2 rounded-md bg-yellow-50 p-2 text-sm text-yellow-800">
            You asked to delete this organization. A super admin has to confirm it.
            <button class="rounded-md bg-white px-2.5 py-1.5 font-semibold ring-1 ring-inset ring-gray-300 hover:bg-gray-50" type="button" @click="withdrawDeletion">
              Withdraw
            </button>
          </div>
          <button v-else class="rounded-md bg-red-600 px-3 py-2 text-sm font-semibold text-white hover:bg-red-500" type="button" @click="requestDeletion">
            Ask to delete organization
          </button>
        </template>
      </section>
    </div>

    <!-- The creator decides about changes of their own role -->
    <div v-if="!managedOrganization && organization?.pendingCreatorChange" class="mt-8 flex flex-wrap items-center gap-2 rounded-md bg-yellow-50 p-4 text-yellow-800">
      {{ organization.pendingCreatorChange.requestedBy || "An admin" }} asks to
      {{ organization.pendingCreatorChange.change === 'REMOVE' ? 'remove you from the organization' : 'make you a user instead of an admin' }}.
      <button class="rounded-md bg-blue-600 px-2.5 py-1.5 text-sm font-semibold text-white hover:bg-blue-500" type="button" @click="decideCreatorChange('confirm')">Confirm</button>
      <button class="rounded-md bg-white px-2.5 py-1.5 text-sm font-semibold ring-1 ring-inset ring-gray-300 hover:bg-gray-50" type="button" @click="decideCreatorChange('decline')">Decline</button>
    </div>

    <p v-if="infoMessage" class="mt-4 text-sm text-blue-700" role="status">{{ infoMessage }}</p>
    <p v-if="errorMessage" class="mt-4 text-sm text-red-600" role="alert">{{ errorMessage }}</p>
  </CenterMain>
</template>

<script setup>
import CenterMain from "@waltid-web-wallet/components/CenterMain.vue";
import LoadingIndicator from "@waltid-web-wallet/components/loading/LoadingIndicator.vue";
import useModalStore from "@waltid-web-wallet/stores/useModalStore.ts";
import {PlusIcon} from "@heroicons/vue/20/solid";
import LinkAddressModal from "~/components/addresses/LinkAddressModal.vue";

const route = useRoute();
const modalStore = useModalStore();

// Super admins manage any organization with /organization?org=<id>
const managedOrganizationId = route.query.org ? String(route.query.org) : null;

const pending = ref(true);
const busy = ref(false);
const errorMessage = ref(null);
const infoMessage = ref(null);
const organization = ref(null);
const superAdmin = ref(false);
const managedOrganization = ref(null);
const members = ref([]);
const addresses = ref([]);
const invites = ref([]);
const copiedCode = ref(null);

const newName = ref("");
const inviteEmail = ref("");
// Personal invite links look like /organization?invite=...
const joinValue = ref(String(route.query.invite || route.query.code || ""));

const adminView = computed(
  () => !!managedOrganization.value || (organization.value?.role === "ADMIN" && organization.value?.status === "ACTIVE"),
);
const users = computed(() => members.value.filter((m) => m.status === "ACTIVE" && m.role === "USER"));
const pendingChange = computed(() => managedOrganization.value?.pendingCreatorChange);
const pendingChangeText = computed(() => {
  const change = pendingChange.value;
  if (!change) return "";
  const what = change.change === "REMOVE" ? "remove the creator" : "make the creator a user";
  return `${change.requestedBy || "An admin"} asks to ${what}.`;
});

/** Admin calls go to the own organization, or to the managed one for super admins */
function adminUrl(path) {
  return managedOrganizationId ? `${path}?organizationId=${managedOrganizationId}` : path;
}

function apiError(err, fallback) {
  return err?.data?.message || fallback;
}

function formatDate(value) {
  return value ? new Date(value).toLocaleDateString() : "";
}

async function load() {
  errorMessage.value = null;
  try {
    const mine = await $fetch("/wallet-api/organization");
    organization.value = mine.organization;
    superAdmin.value = mine.superAdmin;

    if (managedOrganizationId) {
      const all = await $fetch("/wallet-api/organization/all");
      managedOrganization.value = all.find((o) => o.id === managedOrganizationId) || null;
      if (!managedOrganization.value) throw { data: { message: "Organization not found." } };
    }
    if (adminView.value) {
      [members.value, addresses.value, invites.value] = await Promise.all([
        $fetch(adminUrl("/wallet-api/organization/members")),
        $fetch(adminUrl("/wallet-api/organization/addresses")),
        $fetch(adminUrl("/wallet-api/organization/invites")),
      ]);
    }
  } catch (err) {
    errorMessage.value = apiError(err, "Could not load the organization.");
  } finally {
    pending.value = false;
  }
}

async function run(action, fallback) {
  errorMessage.value = null;
  infoMessage.value = null;
  busy.value = true;
  try {
    const result = await action();
    // 202: the change waits for the creator's or a super admin's confirmation
    if (result?.status === "WAITING_FOR_CONFIRMATION") infoMessage.value = result.message;
    await load();
  } catch (err) {
    errorMessage.value = apiError(err, fallback);
  } finally {
    busy.value = false;
  }
}

const createOrganization = () =>
  run(() => $fetch("/wallet-api/organization", { method: "POST", body: { name: newName.value } }), "Could not create the organization.");

function joinOrganization() {
  const value = joinValue.value.trim();
  // Invite codes are 32 hex characters; anything else is treated as the organization's name
  const body = /^[0-9a-f]{32}$/.test(value) ? { inviteCode: value } : { name: value };
  return run(() => $fetch("/wallet-api/organization/join", { method: "POST", body }), "Could not join.");
}

const createInvite = () =>
  run(async () => {
    const invite = await $fetch(adminUrl("/wallet-api/organization/invites"), { method: "POST", body: { email: inviteEmail.value } });
    inviteEmail.value = "";
    await copyInviteLink(invite);
  }, "Could not create the invite.");

const revokeInvite = (invite) =>
  run(() => $fetch(adminUrl(`/wallet-api/organization/invites/${invite.code}`), { method: "DELETE" }), "Could not revoke the invite.");

const approve = (member) =>
  run(() => $fetch(adminUrl(`/wallet-api/organization/members/${member.accountId}/approve`), { method: "POST" }), "Could not accept the request.");

const setRole = (member, role) =>
  run(() => $fetch(adminUrl(`/wallet-api/organization/members/${member.accountId}/role`), { method: "PUT", body: { role } }), "Could not change the role.");

function removeMember(member) {
  const who = member.name || member.email;
  if (!confirm(member.status === "PENDING" ? `Decline the request of ${who}?` : `Remove ${who} from the organization? They lose access to all organization wallets.`)) return;
  return run(() => $fetch(adminUrl(`/wallet-api/organization/members/${member.accountId}`), { method: "DELETE" }), "Could not remove the member.");
}

const decideCreatorChange = (decision) =>
  run(() => $fetch(adminUrl(`/wallet-api/organization/creator-change/${decision}`), { method: "POST" }), "Could not save the decision.");

function confirmName(action) {
  const name = managedOrganization.value?.name || organization.value?.name;
  return prompt(`${action} "${name}" with all its wallets, keys, DIDs and credentials? Type the organization's name to confirm.`) === name;
}

function requestDeletion() {
  if (!confirmName("Ask to delete")) return;
  return run(() => $fetch("/wallet-api/organization/deletion-request", { method: "POST" }), "Could not send the request.");
}

const withdrawDeletion = () =>
  run(() => $fetch("/wallet-api/organization/deletion-request", { method: "DELETE" }), "Could not withdraw the request.");

const declineDeletion = () =>
  run(() => $fetch(adminUrl("/wallet-api/organization/deletion-request/decline"), { method: "POST" }), "Could not decline the request.");

async function deleteOrganization() {
  if (!confirmName("Delete")) return;
  errorMessage.value = null;
  try {
    await $fetch(adminUrl("/wallet-api/organization/delete"), { method: "POST" });
    await navigateTo("/organizations");
  } catch (err) {
    errorMessage.value = apiError(err, "Could not delete the organization.");
  }
}

function removeAddress(entry) {
  if (!confirm(`Remove ${entry.address} from the organization? Members lose access to its wallet.`)) return;
  return run(() => $fetch(adminUrl(`/wallet-api/organization/addresses/${entry.address}`), { method: "DELETE" }), "Could not remove the address.");
}

const toggleAccess = (entry, member, allowed) =>
  run(
    () => $fetch(adminUrl(`/wallet-api/organization/addresses/${entry.address}/access/${member.accountId}`), { method: allowed ? "PUT" : "DELETE" }),
    "Could not change the access.",
  );

function addAddress() {
  modalStore.openModal({
    component: LinkAddressModal,
    props: {
      postUrl: adminUrl("/wallet-api/organization/addresses"),
      title: "Add an organization address",
      description: "The address gets its own wallet, which you can share with members.",
      callback: () => load(),
    },
  });
}

async function copyInviteLink(invite) {
  const link = `${window.location.origin}/organization?invite=${invite.code}`;
  try {
    await navigator.clipboard.writeText(link);
    copiedCode.value = invite.code;
    infoMessage.value = `Invite link for ${invite.email} copied. Send it to them.`;
    setTimeout(() => (copiedCode.value = null), 2000);
  } catch {
    infoMessage.value = `Invite link for ${invite.email}: ${link}`;
  }
}

onMounted(async () => {
  await load();
  // Opened from an invite link while logged in and not yet in an organization: join right away
  if (route.query.invite && !organization.value && !managedOrganizationId) await joinOrganization();
});

definePageMeta({
  layout: "default-reduced-nav",
});

useHead({
  title: "Organization - walt.id",
});
</script>
