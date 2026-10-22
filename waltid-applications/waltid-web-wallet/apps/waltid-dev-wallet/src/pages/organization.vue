<template>
  <CenterMain>
    <h1 class="text-2xl font-semibold mb-2">Organization</h1>

    <LoadingIndicator v-if="pending">Loading organization...</LoadingIndicator>

    <!-- No organization yet: create one or ask to join -->
    <div v-else-if="!organization" class="space-y-8">
      <p class="text-gray-600">
        Wallets belong to organizations. Create an organization for your company, or ask to join an existing one.
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
          Ask to join
        </button>
        <p class="text-xs text-gray-500">An admin of the organization has to accept your request.</p>
      </form>
    </div>

    <!-- Waiting for approval -->
    <div v-else-if="organization.status === 'PENDING'" class="rounded-md bg-yellow-50 p-4 text-yellow-800">
      Your request to join <b>{{ organization.name }}</b> is waiting for an admin to accept it.
    </div>

    <!-- Member -->
    <div v-else-if="organization.role === 'USER'" class="space-y-2">
      <p>You are a member of <b>{{ organization.name }}</b>.</p>
      <p class="text-gray-600">
        The wallets your admins give you access to appear under <NuxtLink class="text-blue-600 underline" to="/">Select wallet</NuxtLink>.
      </p>
    </div>

    <!-- Admin -->
    <div v-else class="space-y-10">
      <p>You are an admin of <b>{{ organization.name }}</b>.</p>

      <section class="space-y-2">
        <h2 class="text-lg font-semibold">Invite</h2>
        <p class="text-sm text-gray-600">
          Share this link, or the code, with your colleagues. They can also ask to join with the organization's name.
          You accept every request below.
        </p>
        <div class="flex flex-wrap items-center gap-3">
          <code class="rounded bg-gray-100 px-2 py-1 text-sm break-all">{{ inviteLink }}</code>
          <button class="rounded-md bg-white px-2.5 py-1.5 text-sm font-semibold text-gray-900 ring-1 ring-inset ring-gray-300 hover:bg-gray-50" type="button" @click="copyInviteLink">
            {{ copied ? "Copied" : "Copy link" }}
          </button>
          <button class="rounded-md bg-white px-2.5 py-1.5 text-sm font-semibold text-gray-900 ring-1 ring-inset ring-gray-300 hover:bg-gray-50" type="button" @click="regenerateInviteCode">
            New code
          </button>
        </div>
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

        <p v-if="addresses && addresses.length === 0" class="text-sm font-semibold mt-2">No addresses yet</p>
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
    </div>

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

const pending = ref(true);
const busy = ref(false);
const errorMessage = ref(null);
const organization = ref(null);
const members = ref([]);
const addresses = ref([]);
const copied = ref(false);

const newName = ref("");
// Invite links look like /organization?code=...
const joinValue = ref(route.query.code ? String(route.query.code) : "");

const users = computed(() => members.value.filter((m) => m.status === "ACTIVE" && m.role === "USER"));
const inviteLink = computed(() =>
  organization.value?.inviteCode && import.meta.client
    ? `${window.location.origin}/organization?code=${organization.value.inviteCode}`
    : "",
);

function apiError(err, fallback) {
  return err?.data?.message || fallback;
}

async function load() {
  errorMessage.value = null;
  try {
    organization.value = (await $fetch("/wallet-api/organization")).organization;
    if (organization.value?.role === "ADMIN" && organization.value?.status === "ACTIVE") {
      [members.value, addresses.value] = await Promise.all([
        $fetch("/wallet-api/organization/members"),
        $fetch("/wallet-api/organization/addresses"),
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
  busy.value = true;
  try {
    await action();
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
  // Invite codes are 24 hex characters; anything else is treated as the organization's name
  const body = /^[0-9a-f]{24}$/.test(value) ? { inviteCode: value } : { name: value };
  return run(() => $fetch("/wallet-api/organization/join", { method: "POST", body }), "Could not send the request.");
}

const regenerateInviteCode = () =>
  run(() => $fetch("/wallet-api/organization/invite-code", { method: "POST" }), "Could not create a new code.");

const approve = (member) =>
  run(() => $fetch(`/wallet-api/organization/members/${member.accountId}/approve`, { method: "POST" }), "Could not accept the request.");

const setRole = (member, role) =>
  run(() => $fetch(`/wallet-api/organization/members/${member.accountId}/role`, { method: "PUT", body: { role } }), "Could not change the role.");

function removeMember(member) {
  const who = member.name || member.email;
  if (!confirm(member.status === "PENDING" ? `Decline the request of ${who}?` : `Remove ${who} from the organization? They lose access to all organization wallets.`)) return;
  return run(() => $fetch(`/wallet-api/organization/members/${member.accountId}`, { method: "DELETE" }), "Could not remove the member.");
}

function removeAddress(entry) {
  if (!confirm(`Remove ${entry.address} from the organization? Members lose access to its wallet.`)) return;
  return run(() => $fetch(`/wallet-api/organization/addresses/${entry.address}`, { method: "DELETE" }), "Could not remove the address.");
}

const toggleAccess = (entry, member, allowed) =>
  run(
    () => $fetch(`/wallet-api/organization/addresses/${entry.address}/access/${member.accountId}`, { method: allowed ? "PUT" : "DELETE" }),
    "Could not change the access.",
  );

function addAddress() {
  modalStore.openModal({
    component: LinkAddressModal,
    props: {
      postUrl: "/wallet-api/organization/addresses",
      title: "Add an organization address",
      description: "The address gets its own wallet, which you can share with members.",
      callback: () => load(),
    },
  });
}

async function copyInviteLink() {
  try {
    await navigator.clipboard.writeText(inviteLink.value);
    copied.value = true;
    setTimeout(() => (copied.value = false), 2000);
  } catch {
    errorMessage.value = "Could not copy the link. Select and copy it manually.";
  }
}

onMounted(load);

definePageMeta({
  layout: "default-reduced-nav",
});

useHead({
  title: "Organization - walt.id",
});
</script>
