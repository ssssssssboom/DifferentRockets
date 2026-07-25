import numpy as np
import matplotlib.pyplot as plt
from matplotlib.animation import FuncAnimation, PillowWriter
from scipy.optimize import brentq
import os


# =====================================================
# Engine
# =====================================================

class RocketEngine:

    def __init__(
        self,
        thrust=500000,
        nozzle_diameter=1.2,
        chamber_pressure=7e6,
        chamber_temperature=3500,
        gamma=1.22,
        gas_constant=355
    ):

        self.F = thrust
        self.D = nozzle_diameter
        self.Pc = chamber_pressure
        self.Tc = chamber_temperature
        self.gamma = gamma
        self.R = gas_constant

        self.area = np.pi*(self.D/2)**2



# =====================================================
# nozzle equations
# =====================================================


def area_ratio(M,gamma):

    return (
        1/M *
        (
            (2/(gamma+1))
            *
            (1+(gamma-1)/2*M*M)
        )
        **
        ((gamma+1)/(2*(gamma-1)))
    )



def solve_exit_mach(engine):

    # typical rocket nozzle
    target_area_ratio=25

    return brentq(
        lambda m:
        area_ratio(
            m,
            engine.gamma
        )
        - target_area_ratio,

        1.01,
        20
    )



def exit_state(engine,ambient):

    g=engine.gamma

    Me=solve_exit_mach(engine)


    Pe=engine.Pc*(
        1+(g-1)/2*Me*Me
    )**(-g/(g-1))


    Te=engine.Tc/(
        1+(g-1)/2*Me*Me
    )


    # expansion condition

    pressure_ratio=Pe/max(ambient,1)


    if pressure_ratio>1:
        state="under_expanded"
    else:
        state="over_expanded"



    return Me,Pe,Te,state




# =====================================================
# plume field
# =====================================================


def generate_plume(
        engine,
        ambient_pressure,
        resolution=700
):


    Me,Pe,Te,state=exit_state(
        engine,
        ambient_pressure
    )


    NPR=engine.Pc/max(
        ambient_pressure,
        1
    )


    # expansion angle

    angle=np.arctan(
        0.15*np.log(NPR)
    )


    # length

    L=(
        engine.D*
        (
        8+
        2*np.sqrt(NPR)
        )
    )


    R0=engine.D/2


    margin=R0*2


    x=np.linspace(
        0,
        L,
        resolution
    )

    y=np.linspace(
        -L*0.35,
        L*0.35,
        resolution//2
    )


    X,Y=np.meshgrid(
        x,y
    )


    # -------------------------
    # plume radius
    # -------------------------

    radius=(
        R0+
        np.tan(angle)*X
    )


    # density distribution

    radial=np.exp(
        -(Y/radius)**4
    )


    axial=np.exp(
        -X/(L*0.55)
    )


    density=radial*axial



    # -------------------------
    # shock diamonds
    # -------------------------


    shock_length=(
        np.pi*
        engine.D*
        Me/
        np.sqrt(Me*Me-1)
    )


    shock=np.cos(
        2*np.pi*
        X/shock_length
    )


    # only center jet has strong diamonds

    diamond=(
        0.5+
        0.5*shock
    )


    density*=(
        0.65+
        0.35*diamond
    )



    # -------------------------
    # temperature field
    # -------------------------


    temperature=(
        density**0.35
    )


    # nozzle exit temperature

    temperature*=Te/engine.Tc



    # -------------------------
    # color intensity
    # -------------------------

    intensity=(
        density*
        (0.5+temperature)
    )


    return (
        X,
        Y,
        intensity,
        density,
        state
    )





# =====================================================
# RGB flame shader
# =====================================================


def flame_color(field):


    f=np.clip(field,0,1)


    rgb=np.zeros(
        f.shape+(3,)
    )


    # hot core

    rgb[:,:,0]=np.minimum(
        1,
        f*3
    )


    rgb[:,:,1]=np.maximum(
        0,
        f*1.8-0.2
    )


    rgb[:,:,2]=np.maximum(
        0,
        f*0.35-0.15
    )


    return rgb





# =====================================================
# render
# =====================================================


def render(
        filename,
        engine,
        pressure
):


    X,Y,I,D,state=generate_plume(
        engine,
        pressure
    )


    # normalize

    I/=I.max()


    rgb=flame_color(I)


    alpha=np.clip(
        D*1.8,
        0,
        1
    )


    rgba=np.dstack(
        (
        rgb,
        alpha
        )
    )


    fig,ax=plt.subplots(
        figsize=(10,4),
        dpi=120
    )


    ax.axis("off")


    img=ax.imshow(
        rgba,
        origin="lower"
    )


    ax.set_xlim(
        0,
        rgba.shape[1]
    )


    ax.set_ylim(
        0,
        rgba.shape[0]
    )


    frames=[]


    for i in range(40):

        phase=i/40*np.pi*2


        wave=(
            1+
            0.05*
            np.sin(
                X*8+phase
            )
        )


        frame=rgba.copy()

        frame[:,:,:3]*=wave[:,:,None]

        frames.append(frame)



    def update(i):

        img.set_data(
            frames[i]
        )

        return img,



    ani=FuncAnimation(
        fig,
        update,
        frames=len(frames),
        interval=50
    )


    os.makedirs(
        "output",
        exist_ok=True
    )


    ani.save(
        "output/"+filename,
        writer=PillowWriter(
            fps=20
        )
    )


    plt.close()



# =====================================================
# main
# =====================================================


if __name__=="__main__":


    engine=RocketEngine(
        thrust=500000,
        nozzle_diameter=1.2,
        chamber_pressure=7e6
    )


    cases={

        "sea_level.gif":
            101325,

        "altitude_10km.gif":
            26000,

        "vacuum.gif":
            1

    }



    for name,p in cases.items():

        print(
            "render",
            name
        )

        render(
            name,
            engine,
            p
        )


    print("done")