package com.deepwistom.atoms.web;

import com.deepwistom.atoms.provider.ProviderException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebEditSourceTest {
    @Test
    void smallSourceIncludingSmallDataUriIsUnchanged() {
        String source = "<img src='data:image/png;base64,YWJjZA=='><script>const x = 'unchanged';</script>";
        var prepared = WebEditSource.prepare(source);
        assertThat(prepared.html()).isEqualTo(source);
        assertThat(prepared.restore(source)).isEqualTo(source);
    }

    @Test
    void multipleAssetsRoundTripExactlyInCssAttributesAndScript() {
        String png = "data:image/png;base64," + "a".repeat(3000);
        String font = "data:font/woff2;base64," + "b".repeat(3000);
        String source = "<style>.hero{background:url(\"" + png + "\")}</style>"
                + "<img src='data:image/png;base64,YWJjZA=='><img src='" + png + "'>"
                + "<script>const font='" + font + "';</script>";
        var prepared = WebEditSource.prepare(source);
        assertThat(prepared.html()).doesNotContain(png, font).contains("data:image/png;base64,YWJjZA==");
        assertThat(prepared.restore(prepared.html())).isEqualTo(source);
    }

    @Test
    void inventedTokenAndExcessiveAssetDuplicationAreRejected() {
        String asset = "data:image/png;base64," + "a".repeat(400_000);
        var prepared = WebEditSource.prepare(asset);
        assertThatThrownBy(() -> prepared.restore(prepared.html().repeat(2))).isInstanceOf(ProviderException.class);
        assertThatThrownBy(() -> prepared.restore("__ATOMS_ASSET_" + "0".repeat(32) + "_999__"))
                .isInstanceOf(ProviderException.class);
    }
}
