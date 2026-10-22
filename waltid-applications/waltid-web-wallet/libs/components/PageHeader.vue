<template>
    <div class="bg-white shadow">
        <div class="px-4 sm:px-6 lg:mx-auto lg:max-w-6xl lg:px-8">
            <!-- Wraps: when there is not enough room, the menu moves below the title instead of overlapping it -->
            <div class="py-6 lg:py-3 flex flex-wrap items-center justify-between gap-x-6 gap-y-4 lg:border-gray-200">
                <div class="min-w-[16rem] flex-1">
                    <!-- --- -->

                    <div class="flex items-center">
                        <slot name="icon"></slot>
                        <div>
                            <div class="flex items-center">
                                <!-- Title -->
                                <slot name="title"></slot>
                            </div>

                            <!-- --- -->
                        </div>
                    </div>
                </div>
                <div class="flex flex-wrap gap-3">
                    <!-- Menu -->
                    <slot name="menu"></slot>
                </div>
            </div>
        </div>
    </div>
</template>

<script>
export default {
    name: "PageHeader",
};
</script>

<style scoped></style>
