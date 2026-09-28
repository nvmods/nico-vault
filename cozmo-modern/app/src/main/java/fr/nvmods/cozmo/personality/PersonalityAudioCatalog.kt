package fr.nvmods.cozmo.personality

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.random.Random

/**
 * Couche audio de personnalité.
 *
 * Les petits SFX 3.6.6 embarqués servent uniquement d'accent mécanique à faible
 * volume. La couche principale est une vocalise procédurale afin d'éviter les
 * clics/grésillements constatés lorsque les SFX Scrn/Srv étaient joués seuls.
 *
 * Les vraies VO 3.6.6 sont bien présentes dans le pack utilisateur, mais elles
 * restent en Wwise Vorbis et nécessitent encore un décodeur dédié avant de
 * pouvoir remplacer cette voix de secours.
 */
internal object PersonalityAudioCatalog {
    fun samples(
        cue: PersonalitySoundCue,
        random: Random = Random.Default
    ): ShortArray {
        val pool = when (cue) {
            PersonalitySoundCue.GREETING -> listOf(HAPPY_A, CURIOUS_A)
            PersonalitySoundCue.HAPPY_SHORT -> listOf(HAPPY_A, HAPPY_B)
            PersonalitySoundCue.HAPPY_LONG -> listOf(HAPPY_A, CURIOUS_B)
            PersonalitySoundCue.CURIOUS -> listOf(CURIOUS_A, CURIOUS_B)
            PersonalitySoundCue.BORED -> listOf(BORED_A, BORED_B)
            PersonalitySoundCue.ANGRY -> listOf(ANGRY_A, ANGRY_B)
            PersonalitySoundCue.SAD -> listOf(SAD_A, SAD_B)
            PersonalitySoundCue.SURPRISED -> listOf(SURPRISED_A, SURPRISED_B, SURPRISED_C)
            PersonalitySoundCue.PICKED_UP -> listOf(SURPRISED_A, SURPRISED_C)
            PersonalitySoundCue.PUT_DOWN -> listOf(CURIOUS_B, HAPPY_B)
            PersonalitySoundCue.EFFORT -> listOf(EFFORT_A, EFFORT_B)
            PersonalitySoundCue.SELF_RIGHT -> listOf(EFFORT_A, EFFORT_B)
            PersonalitySoundCue.CLIFF -> listOf(SURPRISED_A, SURPRISED_B)
            PersonalitySoundCue.SLEEPY -> listOf(BORED_A, SAD_A)
            PersonalitySoundCue.WAKE_UP -> listOf(CURIOUS_A, HAPPY_A)
            PersonalitySoundCue.PLAYFUL -> listOf(HAPPY_A, CURIOUS_A)
            PersonalitySoundCue.WIN -> listOf(HAPPY_A, HAPPY_B)
            PersonalitySoundCue.LOSE -> listOf(SAD_A, BORED_B)
            PersonalitySoundCue.LOW_ENERGY -> listOf(BORED_A, SAD_A)
        }

        val mechanical =
            decode(pool[random.nextInt(pool.size)])
        val vocal =
            PersonalityToneSynth.synthesize(cue, random)

        return mix(
            vocal = vocal,
            mechanical = mechanical
        )
    }

    /**
     * Les SFX 3.6.6 sont surtout des accents mécaniques Scrn/Srv : joués seuls,
     * ils ressemblent à des clics/grésillements. La vocalise procédurale devient
     * donc la couche principale et le SFX officiel reste discret en arrière-plan.
     */
    private fun mix(
        vocal: ShortArray,
        mechanical: ShortArray
    ): ShortArray {
        val size = maxOf(vocal.size, mechanical.size)
        val out = ShortArray(size)

        for (i in 0 until size) {
            val voice =
                if (i < vocal.size) vocal[i].toInt()
                else 0
            val accent =
                if (i < mechanical.size) {
                    (mechanical[i].toInt() * 0.10f).toInt()
                } else {
                    0
                }

            out[i] =
                (voice + accent)
                    .coerceIn(
                        Short.MIN_VALUE.toInt(),
                        Short.MAX_VALUE.toInt()
                    )
                    .toShort()
        }

        return out
    }

    private fun decode(value: String): ShortArray {
        val compressed = Base64.getDecoder().decode(value)
        val raw = GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        val buffer = ByteBuffer.wrap(raw)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val out = ShortArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    private const val HAPPY_A = "H4sIADlVumoC/+2Wv0tCURTHv+/5rMitCIqolmipCPqxBUJS0CC01dYQSEOC0OSQ7wWBU9EP/AMaGhtakoaWKCwoh1wCkyAxxEjSR2gv3/t29U9oUrofOBfu5XLhcM7lfABJs6GAVGFTg0UPMsyI1RI7W5ySSgtnVQ+HbTDZgzKvmLNXawu1cfXQPsiNxrUdr4/6fiQ1slme6ghZry6//aTcOglEOY80u1ClS7zQTFlp+GIfklxBiHnsOkGkvyOuDWvLOk5oDvVwCsZ06XmG+vA5DBgnE9Tzp55tGIE56oPuhzsY0UJAHWMcs7yBnzHscQn3HMIn20XNFfkhJBKJRNLiVuMSHtMp5lqvsJoiB7iGGBaVfka1nBYqhCvL6qXziCDXEeckXoQlmI0ZqDbZ1Jf8b+om64hu/hHdWRF2bjJJL89wrbzbpvOhRLSjYjp7YfuQFXb4JqLAbpTEzSrdDaeT/Sz5K78cyIj6tA8AAA=="

    private const val HAPPY_B = "H4sIADlVumoC/2NgGAUIwMTw+z83w8v/Ogyb/9cy2P+XY8z4Z8X07o87y+6f+7j2vWVoYGiYdnp51NrPJ2f4BcVdY2i46f2/Pu+M2LUP4SEXGBraXX1Ey4/NuC0aHHyFoUHC/X/9t8MMDdIu/+ufO95byNAga79GYs2R7juNQS+A8oc9pgntPLH/ZntI0GWGBj93G5Gjx0TunAosBprL4Z1aUXlK+CZnkFbIga95rK//nGX8+m8Kw5b/Sgxf/jMyMDP8/c/E8B/IGgWjYOABKCWC8L//rAzf/wsznP3/4n/L/1v/9v57yaj8azpzgYCo2PtfWyWv6/nuBACmNdyTdAQAAA=="

    private const val CURIOUS_A = "H4sIADlVumoC/+3OPUuCYRhA4ftVcbDFMUEhJCcR0Xd3ShCClgY3QQiXwL/gg5ODo7gHfkBuNkZLilsIumU6iI5CU4bic4fQX3AQzgVnPyIAAAAAAOD0VB2x6pOdXkhHG/qoK/tjM5rQZ/VoSd4OV9Lbjxy7mXz7B5VhuXZYjqPNr9RcTD6bChdePmMP8VxfjJuuxqftkKuVyEDM631rIqZ4E7oMvIu5u40txGRn9euPbcT7ZLvS06SsNSi/6v2/OAYAAAAAAADgPPwBCsQFodwjAAA="

    private const val CURIOUS_B = "H4sIADlVumoC/2NgGAWjYBSMbPD/PxPD3/9sDF//izDc/W/K4PlPlCGT6RpD9Y/gN2vfunxnu6nF8b9+zn+GBoYGRdMkyatFcf/FvvBw7/iWwCr95xmT4F8jBk5GoV+7mfd9ieGSO5+4u9N6Ac+z4yt+BHFab+hK1/u97kz1WVax19/K1LsF3nz+zRH58yZLz59kpox/rIxJ/yMZrv0//L/1/47/K/9v/L/m//r/a4F4HRIEiWwAyr4GuvIv0LX//jMCXc04GnXDDAAAxk0jq5AGAAA="

    private const val BORED_A = "H4sIADlVumoC/+3YbUxTZxQH8NNCKdUoQlBExbdFcVWnbok6lxFUFBZnlGGCOpmCjMA2Bd1wTBj3EZApLTJfVnWiCLhFSEAoFS2Kg4GiVnROJ28mgiAMdGt9oVDae8+eun3YPi7Lvp1f879pnp770vvc58O5AP8XGUiogEEcCb04DerxY1iGOxS3VQXuSap5Kl+Vv3uhss0t0M2g6HJ9zyVCttrhYs0zv/Z4ZLdbZ3mbyx1T3dsGVX7dIWBjNdV2M0RJn0jR+IoUIWu1zxDjagfYlUcvRuX6uRuvVY9orXisP+xzNC8P2JndwIA18+3BRq16lXe4X+Q4rXfv+FjR2N7Xcl9fiJ6HGhraSlGI6drplWxZU9S0RH0OBZ33gYUuOnnzpk5jI7Cc9JAL7+felc+o0ofW753zcPJceRUKKX7qUM2bpnndrlNN/TciT2esWnImaGF0s232q+Y1R9QTon5vHD5scLkiz+ElvymdgnKcAlaUgRyQbwn596vI+eT8GTnY8QSW4FksQwOWY8XL6P/2qfhr9Cpux+W4ERehP8yC66jDPZiLKzEQQ3AZBvMEoIdkFD0lmaSW/KUAyS6axA6xR2wXVVKJeFb8FUdAE47HZ47ZQ5sGfnwx/enNJ097DB2J912bHLeDTCH12y8e1U8vCjuZrGvOmM8ms0nZY/YuveJzBNh9vvZK04F5pQHL4GuxjsVqy49Vl++/FtOb8pNt97cZpmPKPc5VepFXqbTAfHtuKSvPyS0fLuzs0NdOnXDVYVG3j+6vedAJzL8seqbkB+zFQ+W8smmdJfzbOuOjLXcLwmsu7XKrEZMgFubiU/DFyxCDW+E8zoQ+VIFIK44QQgghhBBC/hNEF96HDgMLjod7GACnMQmi8CZ44keyUVM3x3cleDUCM7LFvL/r2wVsZQawT/cCO1qFQutOFFDwSEGh/wKwnGxgb/H06ceYPXi1D+8bZUeU9oxoZ1VPUMW9Gbw/LOX79oc5R2rWA8vnNYW8rxzgeTS0OKFux/UVlQeAhaXZf0MhsQXY2ExnZ1nFf0+5hcLzHSgkxbSMizQCc+X7rk2beCFu7u54FHSfoRBvBpbAO9F4fp5gntE3fF9HoUgwpKJwil+leS2w9jYU3Hjtdtd898ICYFv52RZdRmFBqpVXzU9e6/1GJbB3coB9wf9hRhIKg4koLLkOTJO5lF9JSy8KK/goOo/H9whIrfny+M78xHe3qbfExa2KORtp2RAfnrl6c8jnixMWnPfvHqNzTbLFNjy31irSxCZ5m/RE1oDRsvUAsjJIhygYDj/jOkmO3zlMg6OGTlkVVvOz2U/sA0LPNxZ7vSETdNnarv1LNZeyvLK+ygrMep5VojFowrUyrU17WhOc/SCzg8/LB3xeuo9LRSMbix8M1Ba4Kxxz5EpMhWQsxcvYKkVIUyQfMd/RNFQ8NNO+zh5k77VPsk9xRNg2OdKt+bbDlo2w7zYwG79vG/jxtjlnkDnvvRuPkseTZz7P1zyBvGIfn5NCPgee3ze5/2IPlRdLNZCLs6AfbegNlZiHJ3kK8AQWYjFW4kGslsqlHNHmCBWnO64O/SCFwR2cCI/RA6yo5E+hC0gv34zQqiSEEEIIIYQQQgghhBBCCCGEEEIIIYQQQgghhBDyT38AEjxBhTBDAAA="

    private const val BORED_B = "H4sIADlVumoC/2NgIB0IMPAxsDA8///0f+f/if/y/i7/0/Fb4vfFnwt+bvmm8r3sc+bnCRcK2l1b+Lc9+1AR87rif32HfFQ/Q4Px+jOCAvc8ngR/X+Ds8FNr3b1neZpy8//Xp5yc9i3tKv/TdROmCBt92jQnwO52M0PDnRU7AxkaFGzfCE232Tkrber/evfVhRHZtQwNSQIMDY/+2cXP0rT9zNCw2fGgeEe39cYlHvNWXPhZsqpPrellCef/+rntr/af5wz9Lc889+9jxs5/4ow2/xsYNv/XZnj9n4Ph33+GUTAKRsEoGAWjYBSMglEwCkbBMAYAWpAgtewMAAA="

    private const val ANGRY_A = "H4sIADlVumoC/2NgGAWjYBSMAvqA//8ZGf7+Z2f4+F+a4eL/cIb5/9sYtvzW/c3C1CLgyhh8bdJUhjv/68WvMjSo2P2vj9rB0MDQkGH0v/7dViCrg6Hhz4M/7Dd+L2XS+efHKPB/NUPn/wCGc//lGD785wCayzgawEMcAADzloXhkAYAAA=="

    private const val ANGRY_B = "H4sIADlVumoC/2NgGAWjYBSMgqEOGBn+/Wdh+P5fgOHpf12Gbf/bGAz+NzAo/rNi3P+ziHE96yxxR7ZTT7/v8pH4Zus8Zbr7g7qVYga6LdvvP4s74ZtofJyhQV1mX57Aebe7zS57JVj3MzQ8UXwR5x+YG14acjRER/L+72JG///pDHv+6zM8+C/K8OU/O8Pf/4wjLJT//2dm+AEM4yf/9Rh2/Q9lYPn/gsHj3/7fzK8Unvc9jzgnennhpXXc/4wz9jE0TG4P4PtacMZqB0/gCa65rD7/6//XSx9kaGBoqLT8X59Uf/YsQ8Osq+Urjjj8r58JDO8/E9JXAwCmhTPbkAYAAA=="

    private const val SAD_A = "H4sIADlVumoC/2NgGAWjYBSMglFAGPz/z8jw9z8rw4//vAxv/ssz3P/vwxD2/8+/rr//GOpYP3Ou5T7AP1ukUPT5860LGbd8C7I+z9CwstN77slVDA1/+hga1FR0S//XT9jF0PDu83aH//UfWX1PMDTMnsLQUOTPfJWhYcN0hgY/l//1jEX/67/uYWiwbQy5Wc578ftMVv4/R5kk/rkyGv6vY9j5X43h3X8OoEsYR6NkFAwpAABh1PnHcAgAAA=="

    private const val SAD_B = "H4sIADlVumoC/2NgGAXI4P9/Roa//1kYfv7nYVj1//b//f/7/u/838DkznzrT8u/TIbj/9UYnv4XZvj0n5PhF1DdP6D6//9Hw20UjIJRMAroBUClLhO4pP79nw1YCh/8r/YPAHz8gpvMBgAA"

    private const val SURPRISED_A = "H4sIADpVumoC/+3W30uTYRQH8O+71WvTSB0KM3azRUWooIgRgokZUWDd5HuRdls4FEIUREl8LFKrG0cZq25eYkYFRT+wKJkwJKJcgpq6RBRjmBSYjvnKbO97evAvCMS787k4F8/F9xx4OHAAxv4fkQKTdmODshGjQvRTEYLUhgZbcXpVZq6zPNudpaf266H5Ukdys9r+ygwqUWsUbaThK7kRJxWWzGCMbZcNScrEIpXhIT2BYd1VXpsn7c1/a9QVQ9/Xt3HwVqjXGW5chbh95t0cRNUF3wTE9HltGuKlVjgFcUU7JKtLuyprpOaxfM+ojUcgDv/xt3u/QOjHP3uePu8usLyXQsEfLdWqTMmvqxiDeF856S4a9HlGSwb1gaPUmfcxMZuhqeMy//dlJQdztAsufKd8RMmDZcqCIXff5N1njO0QRV4otq0LJUkOJMiJBRqhsPU2dc2MmZVWi6H+Sq1HppqGAg/OBiGi/vD9R88O5NTaKX2vOrFnPu207SLdTHxL1C2txep/npgpCA3fGbq+GVh64/O/CEAMH2tcOfKhz/AOzJzqOZc30uG6t1bhiCdX7V3mDcVDrfhELtld2ZqFf4QxxhhjjDHGGGOMMcYY21n/ANj+dfzsIgAA"

    private const val SURPRISED_B = "H4sIADpVumoC/2NgGAWjYOgARoZ//1kYfvznYfjwX5jh7X9eBmaGu/9jGBz/FzKq/F3M5rKIoSGtnaFBvZWhIbNZ85/+/+msDA23Dy1n/1+/drOiyd2nDA1KvouEao4ARR3/ix87cv82Y2jvOYaGrHabq2oCst/ms1X/FmL++jeYUfd/HcOO/2oM7/5zMPz9zzga+KNgFBDMn///MwFzCyswj/IyvP6vz1Dwf+I/4f83/s36Z/iP9d+Gz2desz4/w+L0t/Du+ikMDWE2/+vFDjI0OKtNv8nQYHZ+jc3xnrB+hoZ3iUYbPh856+yzWfkGrwir0cQtT5599356g6Hhraeu0IwTIbdqgl9eYWho9rrMf/rMkcvbYs2cr5/kFVn3hYl98u+DTOH/PjPU/bdjeAbMu/+BAOSu0dgZBaNgFIyCUTAKRsEoGAWjYOABAAyUS+qEEgAA"

    private const val SURPRISED_C = "H4sIADpVumoC/2NgGAUDBRgZ/v9nYvj7n43h238Zhu//9RlE/sv9P/H3xt/Vf7cziv/L/LPvXd1Xre+Vd2y0s5nLdzM0BD+Irfhf31H9v976CkPDSbf/9f/rr19iaAgNnFSvwW+/n6HhXrNoM0MDq83/eoFjDA23bzkLMjTIdTI0WAFFfzX8bJjSwNV4ovl9E0OT8NQf84smMDRot27veLKk5xpDw3t33asMDd6HuOSVN2xZ+b/e9eCH1rCp4j2ak3N7uScV98lPtO2vmbC9X33Cu362CQwT/vSzT/jazzfhfb/AhB/9X/qNJvT0P5kQ3Sc8Oa5T87EnR/RvUeZrf7sYX/07wND4P5DhPNCXn/6zA33MOBr5o2AUjIJRMApGwSgYBaNgFIyCEQMAnjQ2D3gPAAA="

    private const val EFFORT_A = "H4sIADpVumoC/+3QPwsBcRzH8S/9Il1x+ZOSQZlst1iPcgub7XLdcBmklCdgsXsYVzKabzIxO1dsPAAn+ZfC1+9RsHxeD+A9vIkAAOC3mKP04jjdOEUhZ+jMglRac4f6fKQhu616128WGsb+ZLfNcvaa6zljrRJYl6I3CKfV2WG7mcSWwvH1RWnkzpUgqa1qhpdPJx474bytiM26rN9ZlW2FnrL/YXwHAAAAAAAAAAAAAIB/+AIv0ggdlB4AAA=="

    private const val EFFORT_B = "H4sIADpVumoC/3PYwdBQ2pvcz9CwcM3RJSv/sIo1MNheYGjY9am4x6OboUGf48rPhKOb8ic1Nz/2fZt1cO3N+X/2fs9eYfSPhfHPPz7GH/8+Mhj9X8rQ8j+C4dh/FYY3/7kY/vxnZBgFo2AUjIJRMApGwSgYBaNgFIyCUTAKRgEyAAAe7W6ggg8AAA=="
}
