<template>
    <div class="flex min-h-full relative z-10">
        <div
            class="flex flex-1 flex-col justify-center px-4 py-12 sm:px-6 lg:flex-none lg:px-20 xl:px-24 lg:bg-white lg:bg-opacity-50"
        >
            <div
                class="mx-auto w-full max-w-sm lg:w-96 p-3 lg:backdrop-blur-md lg:rounded-3xl lg:shadow lg:bg-neutral-100 lg:bg-opacity-40"
            >
                <div class="">
                    <img
                        :src="logoImg"
                        alt="walt.id logo"
                        class="h-24 lg:h-16 w-auto mx-auto mt-2"
                    />
                    <!-- TODO: i18n system -->
                    <h2 class="mt-4 text-3xl font-bold tracking-tight text-gray-800">
                        Sign in to your SSI wallet
                    </h2>
                    <p v-if="!isOidcLogin" class="mt-2 text-sm text-gray-600">
                        Or {{ " " }}
                        <NuxtLink
                            class="font-medium text-blue-600 hover:text-blue-500"
                            to="/signup"
                        >sign up for your SSI wallet!
                        </NuxtLink>
                    </p>
                    <p v-if="isOidcLogin" class="flex items-center">
                        <!--            <LoadingIndicator> OIDC Login processing... </LoadingIndicator>-->
                    </p>
                </div>

                <div v-if="!isOidcLogin" class="mt-5">
                    <div>

                        </div>
                    

                </div>

                    </div>

                    <div class="mt-6">
                        <form class="space-y-6" @submit.prevent="login">
                            <div>
                                <label
                                    class="block text-sm font-medium leading-6 text-gray-900"
                                    for="email"
                                >
                  <span class="flex flex-row items-center">
                    <EnvelopeIcon class="h-5 mr-1" />
                    Email address
                  </span></label
                                >
                                <div class="mt-2">
                                    <input
                                        id="email"
                                        v-model="emailInput"
                                        :required="true"
                                        autocomplete="email"
                                        autofocus
                                        class="block w-full rounded-md border-0 py-1.5 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-blue-600 sm:text-sm sm:leading-6 px-2 text-gray-600"
                                        name="email"
                                        type="email"
                                    />
                                </div>
                            </div>

                            <div class="space-y-1">
                                <label
                                    class="block text-sm font-medium leading-6 text-gray-900"
                                    for="password"
                                >
                  <span class="flex flex-row items-center">
                    <IdentificationIcon class="h-5 mr-1" />
                    Password
                  </span></label
                                >
                                <div class="mt-2">
                                    <input
                                        id="password"
                                        v-model="passwordInput"
                                        :required="true"
                                        autocomplete="current-password"
                                        class="block w-full rounded-md border-0 py-1.5 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-blue-600 sm:text-sm sm:leading-6 px-2 text-gray-600"
                                        name="password"
                                        type="password"
                                    />
                                </div>
                            </div>

                            <div class="flex flex-col gap-1.5">
                                <button
                                    :class="[
                    success
                      ? 'bg-green-500 hover:bg-green-600 animate-bounce'
                      : 'bg-blue-600  hover:bg-blue-500',
                  ]"
                                    class="flex w-full justify-center rounded-md px-3 py-2 text-sm font-semibold text-white shadow-sm focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600"
                                    type="submit"
                                >
                                    Sign in
                                    <svg
                                        v-if="isLoggingIn"
                                        class="animate-spin ml-1.5 mr-3 h-5 w-5 text-white"
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
                                        ></circle>
                                        <path
                                            class="opacity-75"
                                            d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                                            fill="currentColor"
                                        ></path>
                                    </svg>
                                    <ArrowRightOnRectangleIcon v-else class="ml-1.5 h-5 w-5" />
                                </button>
                            </div>

                            <div class="flex items-center justify-between pb-2">
                                <div class="flex items-center">
                                    <input
                                        id="remember-me"
                                        class="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-600"
                                        name="remember-me"
                                        type="checkbox"
                                    />
                                    <label
                                        class="ml-2 block text-sm text-gray-900"
                                        for="remember-me"
                                    >
                    <span class="flex flex-row items-center">
                      <BookmarkSquareIcon class="-ml-0.5 mr-0.5 h-4 w-4" />
                      Remember me
                    </span></label
                                    >
                                </div>

                                <div class="text-sm">
                                    <a
                                        class="font-medium text-blue-600 hover:text-blue-500"
                                        href="#"
                                    >
                    <span class="flex flex-row items-center">
                      <QuestionMarkCircleIcon class="h-5 w-5 mr-0.5" />
                      Forgot your password?
                    </span>
                                    </a>
                                </div>
                            </div>
                        </form>
                    </div>
                </div>
            </div>
        <div
            class="overflow-hidden max-h-screen fixed left-0 w-full h-full z-0 hidden lg:block"
        >
            <img
                ref="container"
                :class="[isLoggingIn ? 'zoom-in' : 'zoom-out']"
                :src="bgImg"
                alt=""
                class="absolute inset-0 h-full w-full object-cover hidden lg:block"
            />
            <!-- src="https://images.unsplash.com/photo-1529144415895-6aaf8be872fb?ixlib=rb-4.0.3&ixid=MnwxMjA3fDB8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8&auto=format&fit=crop&w=1980&q=80"/> -->

            <!--<div class="relative hidden w-0 flex-1 lg:block">-->
            <!--<img class="absolute inset-0 h-full w-full object-cover" src="https://images.unsplash.com/photo-1567095761054-7a02e69e5c43?ixlib=rb-4.0.3&ixid=MnwxMjA3fDB8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8&auto=format&fit=crop&w=1980&q=80" alt="" />-->
            <!--<img class="absolute inset-0 h-full w-full object-cover" src="https://images.unsplash.com/photo-1541701494587-cb58502866ab?ixlib=rb-4.0.3&ixid=MnwxMjA3fDB8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8&auto=format&fit=crop&w=1980&q=80" alt="" />-->
            <!--<img class="absolute inset-0 h-full w-full object-cover" src="https://images.unsplash.com/photo-1516550893923-42d28e5677af?ixlib=rb-4.0.3&ixid=MnwxMjA3fDB8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8&auto=format&fit=crop&w=1980&q=80" alt="" />-->
            <!--</div>-->
        </div>

        <div
            v-if="showWaltidLoadingSpinner"
            :class="[isLoggingIn ? 'animate-spin' : '']"
            :style="cardStyle"
            class="absolute bottom-3.5 right-3.5 w-10 lg:w-16 h-10 lg:h-16 overflow-hidden"
        >
            <img class="overflow-hidden" src="../public/svg/walt-s.svg" />
        </div>

        <!--suppress PointlessBooleanExpressionJS -->
        <!-- TODO: move transition to @waltid-web-wallet/components/modals/ModalBase.vue -->
        <TransitionRoot :show="error.isError === true" as="template">
            <Dialog as="div" class="relative z-10" @close="closeModal">
                <TransitionChild
                    as="template"
                    enter="ease-out duration-300"
                    enter-from="opacity-0"
                    enter-to="opacity-100"
                    leave="ease-in duration-200"
                    leave-from="opacity-100"
                    leave-to="opacity-0"
                >
                    <div
                        class="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity"
                    >
                    </div>
                </TransitionChild>

                <div class="fixed inset-0 z-10 overflow-y-auto">
                    <div
                        class="flex min-h-full items-end justify-center p-4 text-center sm:items-center sm:p-0"
                    >
                        <TransitionChild
                            as="template"
                            enter="ease-out duration-300"
                            enter-from="opacity-0 translate-y-4 sm:translate-y-0 sm:scale-95"
                            enter-to="opacity-100 translate-y-0 sm:scale-100"
                            leave="ease-in duration-200"
                            leave-from="opacity-100 translate-y-0 sm:scale-100"
                            leave-to="opacity-0 translate-y-4 sm:translate-y-0 sm:scale-95"
                        >
                            <DialogPanel
                                class="relative transform overflow-hidden rounded-lg bg-white px-4 pb-4 pt-5 text-left shadow-xl transition-all sm:my-8 sm:w-full sm:max-w-lg sm:p-6"
                            >
                                <div class="absolute right-0 top-0 hidden pr-4 pt-4 sm:block">
                                    <button
                                        class="rounded-md bg-white text-gray-400 hover:text-gray-500 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2"
                                        type="button"
                                        @click="closeModal"
                                    >
                                        <span class="sr-only">Close</span>
                                        <XMarkIcon aria-hidden="true" class="h-6 w-6" />
                                    </button>
                                </div>
                                <div class="sm:flex sm:items-start">
                                    <div
                                        class="mx-auto flex h-12 w-12 flex-shrink-0 items-center justify-center rounded-full bg-red-100 sm:mx-0 sm:h-10 sm:w-10"
                                    >
                                        <ExclamationCircleIcon
                                            aria-hidden="true"
                                            class="h-6 w-6 text-red-600"
                                        />
                                    </div>
                                    <div class="mt-3 text-center sm:ml-4 sm:mt-0 sm:text-left">
                                        <DialogTitle
                                            as="h3"
                                            class="text-base font-semibold leading-6 text-gray-900"
                                        >
                                            Invalid login
                                        </DialogTitle>
                                        <div class="mt-2">
                                            <p class="text-sm text-gray-500">
                                                {{ error.message }}
                                            </p>
                                        </div>
                                    </div>
                                </div>
                                <div class="mt-5 sm:mt-4 sm:flex sm:flex-row-reverse">
                                    <button
                                        class="mt-3 inline-flex w-full justify-center rounded-md bg-blue-600 px-3 py-2 text-sm font-semibold text-neutral-50 shadow-sm ring-1 ring-inset ring-gray-300 hover:bg-blue-700 sm:mt-0 sm:w-auto"
                                        type="button"
                                        @click="closeModal"
                                    >
                                        Okay
                                    </button>
                                </div>
                            </DialogPanel>
                        </TransitionChild>
                    </div>
                </div>
            </Dialog>
        </TransitionRoot>
        <div
  v-if="showDfnsModal"
  class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
>
  <div class="bg-white rounded-lg shadow-lg w-full max-w-md p-6">
    <h3 class="text-lg font-semibold mb-4">
      Connect with DFNS
    </h3>

    <label class="block mb-2 text-sm font-medium text-gray-700">
      Email Address
    </label>

    <input
      v-model="email"
      type="email"
      placeholder="name@example.com"
      class="w-full px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
      @keyup.enter="submitDfnsLogin"
    />

    <div class="flex justify-end gap-3 mt-6">
      <button
        class="px-4 py-2 text-gray-600 border rounded-lg"
        @click="closeDfnsModal"
      >
        Cancel
      </button>

      <button
        :disabled="loadingDfns || !email"
        class="px-4 py-2 text-white bg-blue-600 rounded-lg disabled:opacity-50"
        @click="submitDfnsLogin"
      >
        {{ loadingDfns ? 'Connecting...' : 'Continue' }}
      </button>
    </div>
  </div>
</div>
<div
  v-if="showRegistrationCodeModal"
  class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
>
  <div class="bg-white rounded-lg shadow-lg w-full max-w-md p-6">
    <h3 class="text-lg font-semibold mb-4">
      Register Passkey
    </h3>

    <p class="text-sm text-gray-500 mb-4">
      Enter the registration code sent to your email to register a passkey for transaction signing.
    </p>

    <label class="block mb-2 text-sm font-medium text-gray-700">
      Registration Code
    </label>

    <input
      v-model="registrationCode"
      type="text"
      placeholder="0000-0000-0000-0000"
      class="w-full px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
      @keyup.enter="submitRegistrationCode"
    />

    <div class="flex justify-end gap-3 mt-6">
      <button
        class="px-4 py-2 text-gray-600 border rounded-lg"
        @click="cancelRegistrationCode"
      >
        Cancel
      </button>

      <button
        :disabled="!registrationCode"
        class="px-4 py-2 text-white bg-blue-600 rounded-lg disabled:opacity-50"
        @click="submitRegistrationCode"
      >
        Register
      </button>
    </div>
  </div>
</div>
</template>

<script lang="ts" setup>
import {
    ArrowRightOnRectangleIcon,
    BookmarkSquareIcon,
    EnvelopeIcon,
    IdentificationIcon,
    QuestionMarkCircleIcon
} from "@heroicons/vue/20/solid";
import {Dialog, DialogPanel, DialogTitle, TransitionChild, TransitionRoot} from "@headlessui/vue";
import {ExclamationCircleIcon, XMarkIcon} from "@heroicons/vue/24/outline";
import {usePageLeave, useParallax} from "@vueuse/core";
import useModalStore from "@waltid-web-wallet/stores/useModalStore.ts";
import {useUserStore} from "@waltid-web-wallet/stores/user.ts";
import {storeToRefs} from "pinia";
import {useTenant} from "@waltid-web-wallet/composables/tenants.ts";
import {decodeJwt} from "jose";
import {MetaMaskSDK} from "@metamask/sdk";
import { WebAuthnSigner } from '@dfns/sdk-browser'
import { DfnsAuthenticator, DfnsApiClient } from '@dfns/sdk'
import { DfnsWallet } from '@dfns/lib-viem'
import { useNuxtApp, useRuntimeConfig } from "nuxt/app";

const store = useModalStore();
const { $auth } = useNuxtApp();
const router = useRouter();

const { data: tenant } = await useTenant();
const name = computed(() => tenant.value?.name);
const bgImg = computed(() => tenant.value?.bgImage || '/images/start-page-background.png');
const logoImg = computed(() => tenant.value?.logoImage || '/svg/waltid.svg');
const showWaltidLoadingSpinner = computed(() => tenant.value?.showWaltidLoadingSpinner);

const isLoggingIn = ref(false);
const error = ref({});
const success = ref(false);

// Prefilled e.g. from an invite link (/login?email=...)
let emailInput = useRoute().query.email ? String(useRoute().query.email) : "";
let passwordInput = "";

const userStore = useUserStore();
const { user } = storeToRefs(userStore);

const { status, data, signIn } = useAuth();

const signInRedirectUrl = ref("/");


const showDfnsModal = ref(false)
const email = ref('')
const loadingDfns = ref(false)

const showRegistrationCodeModal = ref(false)
const registrationCode = ref('')

let registrationCodeResolve: ((value: string | null) => void) | null = null


function promptForRegistrationCode(): Promise<string | null> {
  return new Promise((resolve) => {
    registrationCode.value = ''
    registrationCodeResolve = resolve
    showRegistrationCodeModal.value = true
  })
}

function submitRegistrationCode() {
  if (!registrationCode.value) return
  showRegistrationCodeModal.value = false
  registrationCodeResolve?.(registrationCode.value.trim())
  registrationCodeResolve = null
}

function cancelRegistrationCode() {
  showRegistrationCodeModal.value = false
  registrationCodeResolve?.(null)
  registrationCodeResolve = null
}

const openDfnsModal = () => {
  showDfnsModal.value = true
}

const closeDfnsModal = () => {
  showDfnsModal.value = false
}

const submitDfnsLogin = async () => {
  if (!email.value) return

  loadingDfns.value = true

  try {
    await connectDfns()
    showDfnsModal.value = false

    email.value = ''
  } finally {
    loadingDfns.value = false
  }
}

async function connectOidc() {
    navigateTo("/wallet-api/auth/oidc-login", { external: true });
}

async function login() {
    isLoggingIn.value = true;

    // ktor-authnz: check email + password, then hand the session token to nuxt-auth (same as web3 login)
    const response = await fetch("/wallet-api/auth/account/emailpass", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: emailInput, password: passwordInput })
    }).catch(() => null);
    const result = response?.ok ? await response.json().catch(() => null) : null;

    if (!result?.token) {
        error.value = {
            isError: true,
            message: "Please check that you have entered your correct email address and password!"
        };
        isLoggingIn.value = false;
        return;
    }

    await authnzLogin(emailInput, result.token);
}

function closeModal() {
    error.value = {};
}

const MMSDK = import.meta.client
  ? new MetaMaskSDK({
      dappMetadata: {
        name: "Walt.id Web Wallet",
        url: window.location.href,
      },
      injectProvider: true,
    })
  : null;

async function connectSignerServer() {
  if (!$auth) {
    error.value = {
        isError: true,
        message: "Configuration for Authentik is missing. Please consult deployment environment variables specific for Signer Server."
    };
    return;
  }
  await $auth.signinRedirect();
}


async function openWeb3() {
  await MMSDK.connect();
  const ethereum = MMSDK.getProvider();
    const response = await fetch("/wallet-api/auth/account/web3/nonce", { method: "GET" });
    const challenge = await response.text();


    const accounts = await ethereum.request({ method: "eth_requestAccounts" });
    const address = accounts[0];

    const signature = await ethereum.request({
        method: "personal_sign",
        params: [challenge, address]
    });



    const verificationResponse = await fetch("/wallet-api/auth/account/web3/signed", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
            publicKey: address,
            signed: signature,
            challenge: challenge // Send the full tokenText (JWT)
        })
    });



    const result = await verificationResponse.json();
    await authnzLogin(address, result.token);
}

function openSsoPopup(
  ssoUrl: string,
  redirectUri: string
): Promise<{ code: string; state: string }> {
  return new Promise((resolve, reject) => {
    const width = 500
    const height = 600
    const left = window.screenX + (window.innerWidth - width) / 2
    const top = window.screenY + (window.innerHeight - height) / 2

    const popup = window.open(
      ssoUrl,
      'sso-login',
      `width=${width},height=${height},left=${left},top=${top}`
    )

    if (!popup) {
      return reject(new Error('Popup blocked — please allow popups for this site'))
    }

    const redirectOrigin = new URL(redirectUri).origin
    const redirectPath = new URL(redirectUri).pathname

    const interval = setInterval(() => {
      if (popup.closed) {
        clearInterval(interval)
        reject(new Error('SSO login was cancelled'))
        return
      }

      try {
        // Can only read location once popup redirects back to our origin
        if (
          popup.location.origin === redirectOrigin &&
          popup.location.pathname === redirectPath
        ) {
          const params = new URL(popup.location.href).searchParams
          const code = params.get('code')
          const state = params.get('state')

          clearInterval(interval)
          popup.close()

          if (code && state) {
            resolve({ code, state })
          } else {
            reject(new Error('SSO callback missing code or state'))
          }
        }
      } catch {
        // cross-origin — popup is still on Authentik, keep waiting
      }
    }, 300)
  })
}

async function connectDfns() {
  if (!email.value) return

  const config = useRuntimeConfig()

  const orgId = (config.public.dfnsOrgId || "or-01jo1-70lau-elvati4vg10e11ss") as string
  const baseUrl = config.public.dfnsBaseUrl as string
  const ssoClientId = config.public.clientId as string
  const ssoRedirectUri = (config.public.redirectUri || "https://wallet-dev-stage.oceanenterprise.io/auth/callback") as string
  const relyingParty = (config.public.rpId || "wallet-dev-stage.oceanenterprise.io") as string

  try {

    const ssoInitRes = await fetch(`${baseUrl}/auth/login/sso/init`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        orgId,
        clientId: ssoClientId,
        redirectUri: ssoRedirectUri,
      }),
    })

    if (!ssoInitRes.ok) {
      throw new Error(`SSO init failed: ${ssoInitRes.statusText}`)
    }

    const { ssoRedirectUrl } = await ssoInitRes.json()

    const { code, state } = await openSsoPopup(ssoRedirectUrl, ssoRedirectUri as string)

    const ssoCompleteRes = await fetch(`${baseUrl}/auth/login/sso`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code, state }),
    })

    if (!ssoCompleteRes.ok) {
      throw new Error(`SSO login failed: ${ssoCompleteRes.statusText}`)
    }

    const { token } = await ssoCompleteRes.json()

    let webAuthnSigner = new WebAuthnSigner({
      relyingParty: {
        id: relyingParty,
        name: "Walt.id",
      },
    })

    const dfnsBrowserClient = new DfnsApiClient({
      baseUrl,
      authToken: token,
      signer: webAuthnSigner,
    })


    // Passkey registration mechanism
    const userId = decodeJwt(token)["https://custom/app_metadata"].userId as string
    const userProfile = await dfnsBrowserClient.auth.getUser({
        userId: userId
    })

    if (!userProfile.isRegistered) {

      const registrationCode = await promptForRegistrationCode()
      if (!registrationCode) {
        console.error('Registration code required but not provided')
        router.push('/')
        return
      }

      const dfnsAuth = new DfnsAuthenticator({ baseUrl, signer: webAuthnSigner })

      await dfnsAuth.register({
        orgId,
        username: email.value,
        registrationCode,
      })

    }

    let accounts
    try {
        accounts = await dfnsBrowserClient.wallets.listWallets()
    } catch(err) {
        console.error(`DFNS wallet retrieval failed: ${err.message}`)
    }
    if (accounts?.items.length === 0) {
        console.error(`No wallets created in DFNS`)
        router.push('/')
    }
    const walletId = accounts?.items.filter(wallet => wallet.status === "Active")[0].id as string

    const dfnsWallet = await DfnsWallet.init({
      walletId,
      dfnsClient: dfnsBrowserClient,
    })

    const response = await fetch("/wallet-api/auth/account/web3/nonce", { method: "GET" });
    const challenge = await response.text();

    const signature = await dfnsWallet.signMessage({message: challenge})



    const verificationResponse = await fetch("/wallet-api/auth/account/web3/signed", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
            publicKey: dfnsWallet.address,
            signed: signature,
            challenge: challenge
        })
    });

    const result = await verificationResponse.json();
    await authnzLogin(dfnsWallet.address, result.token);

  } catch (err) {
    console.error(`Login failed: ${err.message}`)
  }
}

definePageMeta({
    title: "Login to your wallet - walt.id",
    layout: "minimal",
    auth: {
        unauthenticatedOnly: true,
        navigateAuthenticatedTo: "/"
    }
});
const container = ref(null);
const parallax = reactive(useParallax(container));
const isLeft = reactive(usePageLeave());
const cardStyle = computed(() => ({
    overflow: "hidden",
    transition: ".5s ease-out all",
    transform: `rotateX(${parallax.source == "mouse" && (isLeft.value || parallax.roll == 0.5) ? 0 : parallax.roll * 60}deg) rotateY(
    ${parallax.source == "mouse" && (isLeft.value || parallax.tilt == -0.5) ? 0 : parallax.tilt * 60}deg)`
}));

useHead({
    title: "Login to your wallet - walt.id"
});
const isOidcLogin = ref(false);

onMounted(() => {
  const route = useRoute();

  if (route.redirectedFrom) {
    signInRedirectUrl.value = route.redirectedFrom.fullPath;
  }

  // e.g. from an invite link: /login?redirect=/invite/<code> (only paths inside the wallet)
  const redirect = route.query.redirect;
  if (typeof redirect === "string" && redirect.startsWith("/") && !redirect.startsWith("//")) {
    signInRedirectUrl.value = redirect;
  }

  isOidcLogin.value = route.query.oidc_login == "true";
});

async function authnzLogin(address, token) {
    isLoggingIn.value = true;

    /*const userData = {
        email: emailInput,
        password: passwordInput
    };*/

    // try {
    await signIn(
        { token: token /*email: emailInput, password: passwordInput, type: "email"*/ },
        { callbackUrl: signInRedirectUrl.value }
    )
        .then(() => {
            user.value = {
                id: "",
                friendlyName: address
            };
        })
        .catch((err) => {
            error.value = {
                isError: true,
                message: "Sign in failed!" //(await response.text())
            };
            isLoggingIn.value = false;
        });
}

async function tryLoginWithOidcSession() {
    const token = await fetch("/wallet-api/auth/oidc-token", {
        redirect: "manual"
    });

    const tokenText = await token.text();

    await signIn(
        {
            email: emailInput,
            token: tokenText,
            type: "oidc"
        },
        { callbackUrl: signInRedirectUrl.value }
    )
        .then(() => {
            user.value = {
                token: tokenText,
                id: "",
                email: decodeJwt(tokenText).email,
                name: decodeJwt(tokenText).name,
                oidcSession: true
            };

        })
        .catch((err) => {
            error.value = {
                isError: true,
                message: "Your OIDC sign in failed."
            };
            isLoggingIn.value = false;
            isOidcLogin.value = false;
        });
}

if (isOidcLogin.value) {
    tryLoginWithOidcSession();
}
</script>

<style scoped>
@keyframes zoom-in {
    25%,
    100% {
        transform: scale(2);
        filter: blur(1rem);
    }
}

@keyframes zoom-out {
    0% {
        transform: scale(2);
    }

    100% {
        transform: scale(1);
    }
}

.zoom-in {
    animation: zoom-in 0.4s normal forwards;
}

.zoom-out {
    animation: zoom-out 0.5s normal forwards;
}
</style>
