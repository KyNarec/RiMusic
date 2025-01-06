package it.fast4x.innertube.models


import kotlinx.serialization.Serializable

@Serializable
data class AccountMenuResponse(
    val actions: List<Action?>?,
) {
    @Serializable
    data class Action(
        val openPopupAction: OpenPopupAction?,
    ) {
        @Serializable
        data class OpenPopupAction(
            val popup: Popup,
        ) {
            @Serializable
            data class Popup(
                val multiPageMenuRenderer: MultiPageMenuRenderer?,
            ) {
                @Serializable
                data class MultiPageMenuRenderer(
                    val header: Header?,
                ) {
                    @Serializable
                    data class Header(
                        val activeAccountHeaderRenderer: ActiveAccountHeaderRenderer?,
                    ) {
                        @Serializable
                        data class ActiveAccountHeaderRenderer(
                            val accountName: Runs?,
                            val email: Runs?,
                            val channelHandle: Runs?,
                            val accountPhoto: Thumbnails?
                        ) {
                            fun toAccountInfo() = AccountInfo(
                                name = accountName?.runs?.first()?.text,
                                email = email?.runs?.first()?.text,
                                channelHandle = channelHandle?.runs?.first()?.text,
                                thumbnailUrl = accountPhoto?.thumbnails?.firstOrNull()?.url?.substringBefore("=")
                            )
                        }
                    }
                }
            }
        }
    }
}
